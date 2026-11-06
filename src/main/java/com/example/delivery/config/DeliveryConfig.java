package com.example.delivery.config;

import com.example.delivery.DeliveryBotMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent configuration for the Automated Delivery Bot.
 * Saved automatically to {@code config/delivery-bot.json}.
 */
public final class DeliveryConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "delivery-bot.json";
	private static DeliveryConfig instance;

	public enum NavigationMode {
		NETHER_MODE("Nether (/home nether)"),
		MULTI_HOME_MODE("Multi-Home (/home h1-h4)");

		private final String label;

		NavigationMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public NavigationMode next() {
			return this == NETHER_MODE ? MULTI_HOME_MODE : NETHER_MODE;
		}
	}

	/** Selected navigation mode: Nether Mode (/home nether) vs Multi-Home Mode (/home h1-h4). */
	public NavigationMode navigationMode = NavigationMode.NETHER_MODE;

	/** Enable or disable continuous coordinate calculations (default: true). When OFF, preserves the most recent calculated H1..H4 coordinates. */
	public boolean coordinateCalculationEnabled = true;

	/** Baseline / saved Nether coordinates for H1, H2, H3, and H4 (updated to match reference image baseline). */
	public int h1NetherX = -416;
	public int h1NetherZ = -829;
	public int h2NetherX = 954;
	public int h2NetherZ = -123;
	public int h3NetherX = -938;
	public int h3NetherZ = 263;
	public int h4NetherX = 475;
	public int h4NetherZ = 920;

	/** Movable HUD X position on screen (default: 10). */
	public int hudX = 10;

	/** Movable HUD Y position on screen (default: 10). */
	public int hudY = 10;

	/** Whether to automatically reconnect after being kicked and run /giaohang huy -> /giaohang huy xacnhan -> restart loop. */
	public boolean autoReconnectAndRecover = true;

	/** Target Y coordinate during fallback flight (default: 150.0). */
	public double targetFlightAltitude = 150.0;

	/** Maximum acceptable horizontal distance to customer after /rtp (default: 5,000 blocks). */
	public double maxRtpDistance = 5000.0;

	/** Maximum /rtp attempts per order (default: 15). */
	public int maxRtpAttempts = 15;

	/** Cooldown in ticks between /rtp command completions (default: 120 ticks = 6.0s). */
	public int rtpCooldownTicks = 120;

	/** Flight speed multiplier (default: 1.0, range: 0.3 .. 2.5). */
	public double flightMovementSpeed = 1.0;

	/** Maximum distance in blocks to interact with NPCs (default: 3.0 blocks). */
	public double customerInteractionDistance = 3.0;

	/** Maximum right-click delivery interaction retries per order (default: 5). */
	public int maxDeliveryRetries = 5;

	/** Whether to render the live status HUD overlay on screen (default: true). */
	public boolean showHudOverlay = true;

	/** Always prioritize and select the most difficult available job with highest reward (default: true). */
	public boolean preferHardestOrder = true;

	/** Allow Baritone to automatically break blocks obstructing the character's path (default: true). */
	public boolean baritoneAllowBreak = true;

	/** Allow Baritone to automatically place blocks (bridging, pillaring, stepping up) during navigation (default: true). */
	public boolean baritoneAllowPlace = true;

	/** Allow Baritone to sprint during automated pathfinding (default: true). */
	public boolean baritoneAllowSprint = true;

	public static synchronized DeliveryConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static Path getConfigPath() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		} catch (Throwable t) {
			return Path.of(FILE_NAME);
		}
	}

	private static DeliveryConfig load() {
		Path path = getConfigPath();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				DeliveryConfig loaded = GSON.fromJson(reader, DeliveryConfig.class);
				if (loaded != null) {
					loaded.clampValues();
					return loaded;
				}
			} catch (Exception e) {
				DeliveryBotMod.LOGGER.warn("Failed to read delivery-bot.json, using defaults", e);
			}
		}
		DeliveryConfig fresh = new DeliveryConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		clampValues();
		Path path = getConfigPath();
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			DeliveryBotMod.LOGGER.warn("Failed to save delivery-bot.json", e);
		}
	}

	public void resetToDefaults() {
		this.navigationMode = NavigationMode.NETHER_MODE;
		this.coordinateCalculationEnabled = true;
		this.h1NetherX = -416;
		this.h1NetherZ = -829;
		this.h2NetherX = 954;
		this.h2NetherZ = -123;
		this.h3NetherX = -938;
		this.h3NetherZ = 263;
		this.h4NetherX = 475;
		this.h4NetherZ = 920;
		this.hudX = 10;
		this.hudY = 10;
		this.autoReconnectAndRecover = true;
		this.targetFlightAltitude = 150.0;
		this.maxRtpDistance = 5000.0;
		this.maxRtpAttempts = 15;
		this.rtpCooldownTicks = 120;
		this.flightMovementSpeed = 1.0;
		this.customerInteractionDistance = 3.0;
		this.maxDeliveryRetries = 5;
		this.showHudOverlay = true;
		this.preferHardestOrder = true;
		this.baritoneAllowBreak = true;
		this.baritoneAllowPlace = true;
		this.baritoneAllowSprint = true;
		save();
	}

	public void clampValues() {
		if (this.navigationMode == null) {
			this.navigationMode = NavigationMode.NETHER_MODE;
		}
		this.hudX = Math.max(0, this.hudX);
		this.hudY = Math.max(0, this.hudY);
		this.targetFlightAltitude = Math.clamp(this.targetFlightAltitude, 60.0, 300.0);
		this.maxRtpDistance = Math.clamp(this.maxRtpDistance, 500.0, 15000.0);
		this.maxRtpAttempts = Math.clamp(this.maxRtpAttempts, 1, 50);
		this.rtpCooldownTicks = Math.clamp(this.rtpCooldownTicks, 20, 600);
		this.flightMovementSpeed = Math.clamp(this.flightMovementSpeed, 0.3, 2.5);
		this.customerInteractionDistance = Math.clamp(this.customerInteractionDistance, 1.5, 5.0);
		this.maxDeliveryRetries = Math.clamp(this.maxDeliveryRetries, 1, 20);
	}
}