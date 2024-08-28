package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import gpsUtil.GpsUtil;
import org.springframework.stereotype.Service;

import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import java.util.concurrent.*;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<Attraction> attractions = gpsUtil.getAttractions();
		Set<String> addedAttractions = ConcurrentHashMap.newKeySet(); // Thread-safe set

		// Create a custom thread pool for handling the reward calculations
		ExecutorService rewardExecutor = new ThreadPoolExecutor(
				Runtime.getRuntime().availableProcessors(),
				Runtime.getRuntime().availableProcessors(),
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>()
		);

		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (VisitedLocation visitedLocation : userLocations) {
			// Pre-filter relevant attractions based on proximity
			List<Attraction> relevantAttractions = attractions.stream()
					.filter(attraction -> !addedAttractions.contains(attraction.attractionName)
							&& nearAttraction(visitedLocation, attraction))
					.collect(Collectors.toList());

			// Process relevant attractions
			CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
				for (Attraction attraction : relevantAttractions) {
					if (addedAttractions.add(attraction.attractionName)) { // Thread-safe add
						user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					}
				}
			}, rewardExecutor);

			futures.add(future);
		}

		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} catch (Exception e) {
			if (e.getCause() instanceof InterruptedException) {
				Thread.currentThread().interrupt(); // Preserve the interrupt status
				System.err.println("Task interrupted: " + e.getMessage());
			} else {
				e.printStackTrace();
			}
		} finally {
			rewardExecutor.shutdown();
		}
	}


	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		double distance = getDistance(attraction, visitedLocation.location);
		return distance <= proximityBuffer;
	}
	
	protected int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
	}

}
