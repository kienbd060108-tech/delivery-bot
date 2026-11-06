package com.example.delivery.navigation;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.model.CustomerTarget;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Dedicated coordinate calculation and persistent data storage module.
 * <ul>
 *   <li>Automatically records every delivery destination's Overworld and Nether {@code (X/8, Z/8)} coordinates.</li>
 *   <li>Uses the reference baseline parameters ({@code H1: (-416, -829)}, {@code H2: (954, -123)},
 *       {@code H3: (-938, 263)}, {@code H4: (475, 920)}) and updates the 4 optimal Nether coordinates
 *       when {@link DeliveryConfig#coordinateCalculationEnabled} is enabled.</li>
 *   <li>When coordinate calculation is disabled, preserves the most recent calculated {@code H1..H4} parameters
 *       without resetting or modifying them, and selects the closest home ({@code /home h1} .. {@code /home h4})
 *       based on those saved parameters.</li>
 *   <li>Persists all recorded destinations and calculated home coordinates to {@code config/delivery-bot-locations.json}.</li>
 * </ul>
 */
public final class DeliveryLocationStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "delivery-bot-locations.json";
	public static final int HOME_COUNT = 4;

	/**
	 * Baseline Nether coordinates for H1, H2, H3, and H4 matching the reference configuration:
	 * <ul>
	 *   <li>H1: (-416, -829)</li>
	 *   <li>H2: (954, -123)</li>
	 *   <li>H3: (-938, 263)</li>
	 *   <li>H4: (475, 920)</li>
	 * </ul>
	 */
	public static final int[][] BASELINE_NETHER_HOMES = {
		{-416, -829},
		{954, -123},
		{-938, 263},
		{475, 920}
	};

	private static DeliveryLocationStorage instance;

	public record RecordedDeliveryPoint(
		String customerName,
		int overworldX,
		int overworldY,
		int overworldZ,
		int netherX,
		int netherZ,
		long timestampMillis
	) {
	}

	public record HomeHubCoordinate(
		int homeIndex,
		int netherX,
		int netherZ,
		int overworldX,
		int overworldZ,
		int clusterSize
	) {
		public String homeName() {
			return "h" + homeIndex;
		}

		public String homeCommand() {
			return "home h" + homeIndex;
		}

		public String setHomeCommand() {
			return "sethome h" + homeIndex;
		}

		public double netherDistanceTo(double targetNetherX, double targetNetherZ) {
			return Math.hypot(targetNetherX - this.netherX, targetNetherZ - this.netherZ);
		}

		public String formattedNetherDisplay() {
			return String.format("H%d:(%d,%d)", homeIndex, netherX, netherZ);
		}
	}

	private static final class PersistedData {
		List<RecordedDeliveryPoint> recordedDeliveries = new ArrayList<>();
		List<HomeHubCoordinate> calculatedHomes = new ArrayList<>();
	}

	private final List<RecordedDeliveryPoint> recordedDeliveries = new ArrayList<>();
	private final List<HomeHubCoordinate> calculatedHomes = new ArrayList<>();

	private DeliveryLocationStorage() {
		resetHomesToBaselineInternal();
	}

	public static synchronized DeliveryLocationStorage getInstance() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static Path getStoragePath() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		} catch (Throwable t) {
			return Path.of(FILE_NAME);
		}
	}

	private static DeliveryLocationStorage load() {
		DeliveryLocationStorage storage = new DeliveryLocationStorage();
		Path path = getStoragePath();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				PersistedData data = GSON.fromJson(reader, PersistedData.class);
				if (data != null) {
					if (data.recordedDeliveries != null) {
						storage.recordedDeliveries.addAll(data.recordedDeliveries);
					}
					if (isValidSavedHomes(data.calculatedHomes)) {
						storage.calculatedHomes.clear();
						storage.calculatedHomes.addAll(data.calculatedHomes);
						storage.syncHomesToConfig();
					} else if (DeliveryConfig.get().coordinateCalculationEnabled && !storage.recordedDeliveries.isEmpty()) {
						storage.recalculateOptimalHomes();
					} else {
						storage.resetHomesToBaselineInternal();
					}
					return storage;
				}
			} catch (Exception e) {
				DeliveryBotMod.LOGGER.warn("[DeliveryLocationStorage] Failed to load delivery-bot-locations.json, using baseline", e);
			}
		}
		storage.resetHomesToBaselineInternal();
		storage.save();
		return storage;
	}

	private static boolean isValidSavedHomes(List<HomeHubCoordinate> homes) {
		if (homes == null || homes.size() != HOME_COUNT) {
			return false;
		}
		// Reject legacy (-625, -625) placeholder quadrants so the new baseline is applied
		for (HomeHubCoordinate h : homes) {
			if (h == null || (Math.abs(h.netherX()) == 625 && Math.abs(h.netherZ()) == 625)) {
				return false;
			}
		}
		return true;
	}

	public synchronized void save() {
		syncHomesToConfig();
		Path path = getStoragePath();
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			PersistedData data = new PersistedData();
			data.recordedDeliveries = new ArrayList<>(this.recordedDeliveries);
			data.calculatedHomes = new ArrayList<>(this.calculatedHomes);
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			DeliveryBotMod.LOGGER.warn("[DeliveryLocationStorage] Failed to save delivery-bot-locations.json", e);
		}
	}

	private void syncHomesToConfig() {
		if (this.calculatedHomes.size() < HOME_COUNT) {
			return;
		}
		DeliveryConfig cfg = DeliveryConfig.get();
		cfg.h1NetherX = this.calculatedHomes.get(0).netherX();
		cfg.h1NetherZ = this.calculatedHomes.get(0).netherZ();
		cfg.h2NetherX = this.calculatedHomes.get(1).netherX();
		cfg.h2NetherZ = this.calculatedHomes.get(1).netherZ();
		cfg.h3NetherX = this.calculatedHomes.get(2).netherX();
		cfg.h3NetherZ = this.calculatedHomes.get(2).netherZ();
		cfg.h4NetherX = this.calculatedHomes.get(3).netherX();
		cfg.h4NetherZ = this.calculatedHomes.get(3).netherZ();
	}

	private void resetHomesToBaselineInternal() {
		this.calculatedHomes.clear();
		for (int i = 0; i < HOME_COUNT; i++) {
			int nx = BASELINE_NETHER_HOMES[i][0];
			int nz = BASELINE_NETHER_HOMES[i][1];
			this.calculatedHomes.add(new HomeHubCoordinate(i + 1, nx, nz, nx * 8, nz * 8, 0));
		}
		syncHomesToConfig();
	}

	/**
	 * Resets the 4 calculated homes to the baseline parameters from the reference image:
	 * {@code H1:(-416,-829)}, {@code H2:(954,-123)}, {@code H3:(-938,263)}, {@code H4:(475,920)}.
	 */
	public synchronized void resetHomesToBaseline() {
		resetHomesToBaselineInternal();
		save();
	}

	/**
	 * Records a delivery destination.
	 * <ul>
	 *   <li>When {@link DeliveryConfig#coordinateCalculationEnabled} is {@code true}, continuously records
	 *       the destination and recalculates/updates the 4 Nether home coordinates.</li>
	 *   <li>When {@link DeliveryConfig#coordinateCalculationEnabled} is {@code false}, preserves the most recent
	 *       calculated parameters ({@code H1..H4}) without resetting or modifying them.</li>
	 * </ul>
	 */
	public synchronized void recordDeliveryDestination(CustomerTarget target) {
		if (target == null) {
			return;
		}
		if (!DeliveryConfig.get().coordinateCalculationEnabled) {
			DeliveryBotMod.LOGGER.info(
				"[DeliveryLocationStorage] Coordinate calculation is OFF; preserving saved H1..H4 parameters for destination {} ({}, {})",
				target.customerName(), target.netherX(), target.netherZ()
			);
			return;
		}

		int nx = target.netherX();
		int nz = target.netherZ();

		// Avoid adding consecutive identical coordinates
		if (!this.recordedDeliveries.isEmpty()) {
			RecordedDeliveryPoint last = this.recordedDeliveries.getLast();
			if (last.overworldX() == target.x() && last.overworldZ() == target.z()) {
				return;
			}
		}

		RecordedDeliveryPoint point = new RecordedDeliveryPoint(
			target.customerName(),
			target.x(),
			target.y(),
			target.z(),
			nx,
			nz,
			System.currentTimeMillis()
		);
		this.recordedDeliveries.add(point);
		recalculateOptimalHomes();
		save();

		DeliveryBotMod.LOGGER.info(
			"[DeliveryLocationStorage] Recorded destination #{} ({} @ Overworld {}, {} -> Nether {}, {}). Updated 4 optimal homes (H1..H4).",
			this.recordedDeliveries.size(), target.customerName(), target.x(), target.z(), nx, nz
		);
	}

	/**
	 * Clears all recorded delivery destinations and resets the 4 homes to the baseline coordinates:
	 * {@code H1:(-416,-829)}, {@code H2:(954,-123)}, {@code H3:(-938,263)}, {@code H4:(475,920)}.
	 */
	public synchronized void clearAllRecordedLocations() {
		this.recordedDeliveries.clear();
		resetHomesToBaselineInternal();
		save();
	}

	/**
	 * Calculates the 4 Nether coordinates closest to all recorded delivery destinations using
	 * deterministic k-means clustering (k = 4) anchored to {@link #BASELINE_NETHER_HOMES}.
	 */
	public synchronized void recalculateOptimalHomes() {
		int n = this.recordedDeliveries.size();
		if (n == 0) {
			resetHomesToBaselineInternal();
			return;
		}

		this.calculatedHomes.clear();

		// Initialize centroids from the 4 reference baseline coordinates:
		// H1: (-416, -829), H2: (954, -123), H3: (-938, 263), H4: (475, 920)
		double[] centerX = new double[HOME_COUNT];
		double[] centerZ = new double[HOME_COUNT];
		for (int k = 0; k < HOME_COUNT; k++) {
			centerX[k] = BASELINE_NETHER_HOMES[k][0];
			centerZ[k] = BASELINE_NETHER_HOMES[k][1];
		}

		int[] assignments = new int[n];
		int[] counts = new int[HOME_COUNT];

		for (int iter = 0; iter < 30; iter++) {
			boolean changed = false;
			for (int i = 0; i < n; i++) {
				RecordedDeliveryPoint pt = this.recordedDeliveries.get(i);
				int bestCluster = 0;
				double bestDistSq = Double.MAX_VALUE;
				for (int k = 0; k < HOME_COUNT; k++) {
					double dx = pt.netherX() - centerX[k];
					double dz = pt.netherZ() - centerZ[k];
					double dSq = dx * dx + dz * dz;
					if (dSq < bestDistSq) {
						bestDistSq = dSq;
						bestCluster = k;
					}
				}
				if (assignments[i] != bestCluster) {
					assignments[i] = bestCluster;
					changed = true;
				}
			}
			if (!changed && iter > 0) {
				break;
			}

			double[] sumX = new double[HOME_COUNT];
			double[] sumZ = new double[HOME_COUNT];
			counts = new int[HOME_COUNT];

			for (int i = 0; i < n; i++) {
				int cluster = assignments[i];
				RecordedDeliveryPoint pt = this.recordedDeliveries.get(i);
				sumX[cluster] += pt.netherX();
				sumZ[cluster] += pt.netherZ();
				counts[cluster]++;
			}

			for (int k = 0; k < HOME_COUNT; k++) {
				if (counts[k] > 0) {
					centerX[k] = sumX[k] / counts[k];
					centerZ[k] = sumZ[k] / counts[k];
				} else {
					centerX[k] = BASELINE_NETHER_HOMES[k][0];
					centerZ[k] = BASELINE_NETHER_HOMES[k][1];
				}
			}
		}

		for (int i = 0; i < HOME_COUNT; i++) {
			int nx = (int) Math.round(centerX[i]);
			int nz = (int) Math.round(centerZ[i]);
			this.calculatedHomes.add(new HomeHubCoordinate(
				i + 1,
				nx,
				nz,
				nx * 8,
				nz * 8,
				counts[i]
			));
		}
		syncHomesToConfig();
	}

	/**
	 * Finds which of the 4 saved/calculated homes ({@code h1}, {@code h2}, {@code h3}, or {@code h4})
	 * is closest to the given customer destination.
	 * Uses the saved parameters in {@link #calculatedHomes} without modifying them.
	 */
	public synchronized HomeHubCoordinate findNearestHome(CustomerTarget target) {
		if (this.calculatedHomes.isEmpty()) {
			resetHomesToBaselineInternal();
		}
		if (target == null) {
			return this.calculatedHomes.getFirst();
		}

		int targetNx = target.netherX();
		int targetNz = target.netherZ();

		HomeHubCoordinate best = this.calculatedHomes.getFirst();
		double bestDist = best.netherDistanceTo(targetNx, targetNz);

		for (int i = 1; i < this.calculatedHomes.size(); i++) {
			HomeHubCoordinate candidate = this.calculatedHomes.get(i);
			double d = candidate.netherDistanceTo(targetNx, targetNz);
			if (d < bestDist) {
				bestDist = d;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Returns the {@link HomeHubCoordinate} for {@code homeIndex} ({@code 1..4}).
	 */
	public synchronized HomeHubCoordinate getHomeByIndex(int homeIndex) {
		if (this.calculatedHomes.isEmpty()) {
			resetHomesToBaselineInternal();
		}
		int clamped = Math.clamp(homeIndex, 1, HOME_COUNT);
		for (HomeHubCoordinate h : this.calculatedHomes) {
			if (h.homeIndex() == clamped) {
				return h;
			}
		}
		return this.calculatedHomes.get(clamped - 1);
	}

	public synchronized List<HomeHubCoordinate> getCalculatedHomes() {
		if (this.calculatedHomes.isEmpty()) {
			resetHomesToBaselineInternal();
		}
		return List.copyOf(this.calculatedHomes);
	}

	public synchronized List<RecordedDeliveryPoint> getRecordedDeliveries() {
		return List.copyOf(this.recordedDeliveries);
	}

	public synchronized int getRecordedCount() {
		return this.recordedDeliveries.size();
	}
}