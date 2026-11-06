package com.example.delivery.automation;

/**
 * States for the automated delivery bot state machine with Baritone API navigation,
 * Multi-Home / Nether Mode routing, and automatic kick recovery.
 */
public enum AutomationState {
	IDLE("Idle", 0),
	WARPING_TO_PICKUP("Teleporting /home lambanh", 1),
	WAITING_FOR_PICKUP("Waiting for Bakery Home", 1),
	OPENING_DELIVERY_MENU("Opening /giaohang Menu", 2),
	SELECTING_ORDER("Selecting Hardest Job", 2),
	INTERACTING_WITH_BAKERY_NPC("Baritone -> TIỆM BÁNH", 3),
	COLLECTING_PACKAGE("Collecting Package", 3),
	READING_CUSTOMER_COORDINATES("Reading & Saving Coords", 4),
	TELEPORTING_HOME_NETHER("Teleporting to Hub Home", 5),
	NAVIGATING_IN_NETHER("Baritone -> Nether (X/8, Z/8)", 5),
	BUILDING_NETHER_PORTAL("Building Nether Portal", 6),
	ENTERING_NETHER_PORTAL("Entering Portal -> Overworld", 6),
	NAVIGATING_TO_CUSTOMER("Baritone -> Customer", 7),
	LOCATING_CUSTOMER("Approaching Customer NPC", 7),
	DELIVERING_PACKAGE("Delivering Package", 8),
	VERIFYING_DELIVERY("Verifying Reward", 8),
	DELIVERY_COMPLETED("Delivery Completed!", 8),
	STARTING_NEXT_ORDER("Starting Next Job", 8),
	SETTING_HOME_TELEPORT_NETHER("Auto-Home: /home nether", 2),
	SETTING_HOME_NAVIGATING("Auto-Home: Baritone Navigating", 5),
	SETTING_HOME_EXECUTING("Auto-Home: /sethome h#", 8),
	RECONNECTING_AFTER_KICK("Reconnecting to Server...", 1),
	RECOVERING_CANCEL_ORDER("Recovering (/giaohang huy)", 1),
	PAUSED("Paused", 0),
	ERROR("Error", 0);

	public static final int TOTAL_STEPS = 8;

	private final String displayName;
	private final int stepIndex;

	AutomationState(String displayName, int stepIndex) {
		this.displayName = displayName;
		this.stepIndex = stepIndex;
	}

	public String displayName() {
		return displayName;
	}

	public int stepIndex() {
		return stepIndex;
	}

	public float progressRatio() {
		if (this == DELIVERY_COMPLETED || this == STARTING_NEXT_ORDER) {
			return 1.0f;
		}
		if (stepIndex <= 0) {
			return 0.0f;
		}
		return Math.min(1.0f, (float) stepIndex / (float) TOTAL_STEPS);
	}

	public boolean isActiveRunning() {
		return this != IDLE && this != PAUSED && this != ERROR;
	}
}
