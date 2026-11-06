package com.example.delivery.navigation;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.model.CustomerTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Controls smooth camera rotation, ground walking toward the {@code TIỆM BÁNH} NPC,
 * flight activation ({@code /fly}), ascending to {@code Y = 150}, smooth horizontal flight
 * to the exact destination coordinates, and double-tapping SPACEBAR to disable flight and fall straight down.
 */
public final class FlightNavigator {
	private static final float MAX_YAW_STEP_DEG = 24.0f;
	private static final float MAX_PITCH_STEP_DEG = 16.0f;

	private FlightNavigator() {
	}

	/**
	 * Immediately releases all automated movement keys (forward, back, left, right, jump, sneak, sprint).
	 */
	public static void releaseAllMovementKeys(Minecraft mc) {
		if (mc == null || mc.options == null) {
			return;
		}
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	/**
	 * Rotates the player's view (yaw and pitch) smoothly toward {@code targetPoint}.
	 *
	 * @return angular distance in degrees remaining after this tick's rotation step.
	 */
	public static double rotateTowards(LocalPlayer player, Vec3 targetPoint, float maxYawStep, float maxPitchStep) {
		if (player == null || targetPoint == null) {
			return 0.0;
		}
		Vec3 eyes = player.getEyePosition();
		double dx = targetPoint.x - eyes.x;
		double dy = targetPoint.y - eyes.y;
		double dz = targetPoint.z - eyes.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);

		float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
		float desiredPitch = (float) (-(Mth.atan2(dy, Math.max(0.001, horiz)) * (180.0 / Math.PI)));
		desiredPitch = Mth.clamp(desiredPitch, -89.0f, 89.0f);

		float yawDiff = Mth.wrapDegrees(desiredYaw - player.getYRot());
		float pitchDiff = desiredPitch - player.getXRot();

		float appliedYaw = player.getYRot() + Mth.clamp(yawDiff, -maxYawStep, maxYawStep);
		float appliedPitch = Mth.clamp(player.getXRot() + Mth.clamp(pitchDiff, -maxPitchStep, maxPitchStep), -89.0f, 89.0f);

		player.setYRot(appliedYaw);
		player.setXRot(appliedPitch);
		player.yHeadRot = appliedYaw;

		float remYaw = Math.abs(Mth.wrapDegrees(desiredYaw - appliedYaw));
		float remPitch = Math.abs(desiredPitch - appliedPitch);
		return Math.hypot(remYaw, remPitch);
	}

	/**
	 * Approaches an NPC on the ground (such as {@code TIỆM BÁNH} at {@code /warp tuban}).
	 *
	 * @return true once the player's eye/body distance to {@code npc} is within {@code interactionRange}
	 *         and the camera is aimed at the NPC.
	 */
	public static boolean approachNpcOnGround(Minecraft mc, Entity npc, double interactionRange) {
		if (mc == null || mc.player == null || mc.options == null || npc == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		Vec3 targetCenter = npc.getBoundingBox().getCenter();
		double angleRem = rotateTowards(player, targetCenter, MAX_YAW_STEP_DEG, MAX_PITCH_STEP_DEG);
		double dist = player.getEyePosition().distanceTo(targetCenter);

		if (dist <= interactionRange) {
			releaseAllMovementKeys(mc);
			return angleRem <= 12.0;
		}

		mc.options.keyUp.setDown(true);
		mc.options.keySprint.setDown(dist > 6.0);
		mc.options.keyJump.setDown(player.horizontalCollision && player.onGround());
		mc.options.keyShift.setDown(false);
		return false;
	}

	/**
	 * Ensures the player is actively flying after {@code /fly} has enabled {@code mayfly}.
	 */
	public static boolean ensureActivelyFlying(Minecraft mc) {
		if (mc == null || mc.player == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		if (player.getAbilities().flying) {
			return true;
		}
		if (player.getAbilities().mayfly) {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			return true;
		}
		return false;
	}

	/**
	 * Executes a tick-based double-tap of the SPACEBAR ({@code mc.options.keyJump}) to disable flight
	 * so the player falls straight down:
	 * <ul>
	 *   <li>Step 0: release {@code keyJump} (ensures {@code wasJumping == false})</li>
	 *   <li>Step 1: press {@code keyJump} (first tap — sets {@code jumpTriggerTime = 7} in {@code LocalPlayer#aiStep})</li>
	 *   <li>Step 2: release {@code keyJump} (resets {@code wasJumping = false})</li>
	 *   <li>Step 3: press {@code keyJump} (second tap — toggles {@code abilities.flying = false} and syncs abilities)</li>
	 *   <li>Step 4+: release {@code keyJump} and verify {@code abilities.flying == false}</li>
	 * </ul>
	 *
	 * @return true once the SPACEBAR double-tap is complete and flight is disabled.
	 */
	public static boolean doubleTapSpacebarToDisableFlightTick(Minecraft mc, int step) {
		if (mc == null || mc.player == null || mc.options == null) {
			return true;
		}
		LocalPlayer player = mc.player;

		// Keep horizontal movement completely stopped so the player falls straight down
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keySprint.setDown(false);

		Vec3 vel = player.getDeltaMovement();
		player.setDeltaMovement(0.0, Math.min(vel.y, 0.0), 0.0);

		switch (step) {
			case 0 -> {
				mc.options.keyJump.setDown(false);
				return false;
			}
			case 1 -> {
				mc.options.keyJump.setDown(true);
				return false;
			}
			case 2 -> {
				mc.options.keyJump.setDown(false);
				return false;
			}
			case 3 -> {
				mc.options.keyJump.setDown(true);
				return false;
			}
			default -> {
				mc.options.keyJump.setDown(false);
				if (player.getAbilities().flying) {
					player.getAbilities().flying = false;
					player.onUpdateAbilities();
				}
				DeliveryBotMod.LOGGER.info("[FlightNavigator] Double-tapped SPACEBAR -> flight disabled, falling straight down");
				return true;
			}
		}
	}

	/**
	 * Ascends (or adjusts vertical altitude) until the player's actual Y coordinate reaches approximately
	 * {@code targetAltitude} (default: {@code Y = 150.0}).
	 */
	public static boolean ascendToAltitudeTick(Minecraft mc, double targetAltitude) {
		if (mc == null || mc.player == null || mc.options == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		ensureActivelyFlying(mc);

		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keySprint.setDown(false);

		double currentY = player.getY();
		double diffY = targetAltitude - currentY;

		if (Math.abs(diffY) <= 1.5) {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(false);
			return true;
		}

		if (diffY > 0) {
			mc.options.keyJump.setDown(true);
			mc.options.keyShift.setDown(false);
		} else {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(true);
		}
		return false;
	}

	/**
	 * Navigates the player horizontally in the Nether above the Bedrock roof ({@code Y ≈ 130.0})
	 * directly to the calculated Nether coordinates {@code (customerX / 8, customerZ / 8)}.
	 *
	 * @return true once the player is positioned directly over {@code (netherX, netherZ)}.
	 */
	public static boolean flyTowardsNetherTargetTick(
		Minecraft mc,
		CustomerTarget target,
		DeliveryConfig config,
		int stateTicks
	) {
		if (mc == null || mc.player == null || mc.options == null || target == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		ensureActivelyFlying(mc);

		double destX = target.netherX() + 0.5;
		double destZ = target.netherZ() + 0.5;
		double dx = destX - player.getX();
		double dz = destZ - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);

		Vec3 horizontalLookTarget = new Vec3(destX, player.getEyeY(), destZ);
		if (horizDist > 0.25) {
			rotateTowards(player, horizontalLookTarget, MAX_YAW_STEP_DEG, MAX_PITCH_STEP_DEG);
		}

		if (horizDist <= 0.85) {
			releaseAllMovementKeys(mc);
			Vec3 vel = player.getDeltaMovement();
			player.setDeltaMovement(0.0, vel.y * 0.2, 0.0);
			return true;
		}

		// Cruise ~2.5 blocks above the Y=128 Nether Bedrock roof
		double netherCruiseY = 130.5;
		double dy = netherCruiseY - player.getY();
		if (player.horizontalCollision || dy > 1.0) {
			mc.options.keyJump.setDown(true);
			mc.options.keyShift.setDown(false);
		} else if (dy < -1.5 && player.getY() > 129.2) {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(true);
		} else {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(false);
		}

		float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
		float yawError = Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot()));

		if (yawError > 35.0f) {
			mc.options.keyUp.setDown(false);
			mc.options.keySprint.setDown(false);
		} else if (horizDist > 32.0) {
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(config.flightMovementSpeed >= 0.9);
			applySpeedMultiplier(player, config.flightMovementSpeed, 1.0);
		} else if (horizDist > 10.0) {
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.7);
		} else if (horizDist > 3.0) {
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.4);
			Vec3 vel = player.getDeltaMovement();
			double horizSpeed = Math.hypot(vel.x, vel.z);
			if (horizSpeed > 0.20) {
				player.setDeltaMovement(vel.x * 0.55, vel.y, vel.z * 0.55);
			}
		} else {
			boolean pulseForward = (stateTicks % 2 == 0);
			mc.options.keyUp.setDown(pulseForward);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.25);
			Vec3 vel = player.getDeltaMovement();
			double horizSpeed = Math.hypot(vel.x, vel.z);
			if (horizSpeed > 0.10) {
				player.setDeltaMovement(vel.x * 0.4, vel.y, vel.z * 0.4);
			}
		}

		return false;
	}

	/**
	 * Navigates the player horizontally in flight at {@code Y ≈ config.targetFlightAltitude} (default 150)
	 * directly to the exact {@code (X, Z)} column of the customer destination without overshooting.
	 *
	 * @return true once the player is positioned directly over the exact {@code (X, Z)} destination
	 *         (ready to double-tap SPACEBAR and fall straight down).
	 */
	public static boolean flyTowardsCustomerTick(
		Minecraft mc,
		CustomerTarget target,
		Entity detectedCustomerEntity,
		DeliveryConfig config,
		int stateTicks
	) {
		if (mc == null || mc.player == null || mc.options == null || target == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		ensureActivelyFlying(mc);

		Vec3 destPos = detectedCustomerEntity != null
			? detectedCustomerEntity.getBoundingBox().getCenter()
			: new Vec3(target.x() + 0.5, target.y() + 1.0, target.z() + 0.5);

		double dx = destPos.x - player.getX();
		double dz = destPos.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);

		// Aim horizontally toward the destination column
		Vec3 horizontalLookTarget = new Vec3(destPos.x, player.getEyeY(), destPos.z);
		if (horizDist > 0.25) {
			rotateTowards(player, horizontalLookTarget, MAX_YAW_STEP_DEG, MAX_PITCH_STEP_DEG);
		}

		// Once horizontally aligned over the exact destination column, stop and trigger vertical drop!
		if (horizDist <= 0.9) {
			releaseAllMovementKeys(mc);
			Vec3 vel = player.getDeltaMovement();
			player.setDeltaMovement(0.0, vel.y * 0.2, 0.0);
			return true;
		}

		// Maintain cruise altitude around Y = config.targetFlightAltitude (150), or higher if customer/terrain is higher
		double cruiseAltitude = Math.max(config.targetFlightAltitude, destPos.y + 4.0);
		double dy = cruiseAltitude - player.getY();

		if (player.horizontalCollision) {
			// Climb over high mountain terrain or trees obstructing horizontal flight
			mc.options.keyJump.setDown(true);
			mc.options.keyShift.setDown(false);
		} else if (dy > 1.2) {
			mc.options.keyJump.setDown(true);
			mc.options.keyShift.setDown(false);
		} else if (dy < -1.2) {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(true);
		} else {
			mc.options.keyJump.setDown(false);
			mc.options.keyShift.setDown(false);
		}

		// Horizontal movement & speed modulation to reach exact (X, Z) without overshooting
		float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
		float yawError = Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot()));

		if (yawError > 35.0f) {
			mc.options.keyUp.setDown(false);
			mc.options.keySprint.setDown(false);
		} else if (horizDist > 32.0) {
			// Full cruise speed
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(config.flightMovementSpeed >= 0.9);
			applySpeedMultiplier(player, config.flightMovementSpeed, 1.0);
		} else if (horizDist > 10.0) {
			// Medium approach speed (no sprint)
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.7);
		} else if (horizDist > 3.0) {
			// Slow approach speed
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.4);
			Vec3 vel = player.getDeltaMovement();
			double horizSpeed = Math.hypot(vel.x, vel.z);
			if (horizSpeed > 0.20) {
				player.setDeltaMovement(vel.x * 0.55, vel.y, vel.z * 0.55);
			}
		} else {
			// Precision centering over exact (destPos.x, destPos.z) column (horizDist <= 3.0)
			boolean pulseForward = (stateTicks % 2 == 0);
			mc.options.keyUp.setDown(pulseForward);
			mc.options.keySprint.setDown(false);
			applySpeedMultiplier(player, config.flightMovementSpeed, 0.25);
			Vec3 vel = player.getDeltaMovement();
			double horizSpeed = Math.hypot(vel.x, vel.z);
			if (horizSpeed > 0.10) {
				player.setDeltaMovement(vel.x * 0.4, vel.y, vel.z * 0.4);
			}
		}

		return false;
	}

	/**
	 * Keeps the player horizontally centered over {@code (destPos.x, destPos.z)} while falling straight down
	 * or standing on an obstructing block waiting for it to break.
	 */
	public static void maintainHorizontalColumnWhileDescending(Minecraft mc, Vec3 destPos, boolean currentlyMiningBlock) {
		if (mc == null || mc.player == null || mc.options == null || destPos == null) {
			return;
		}
		LocalPlayer player = mc.player;
		double dx = destPos.x - player.getX();
		double dz = destPos.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);

		mc.options.keyJump.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keySprint.setDown(false);

		if (horizDist <= 0.75) {
			mc.options.keyUp.setDown(false);
			Vec3 vel = player.getDeltaMovement();
			player.setDeltaMovement(vel.x * 0.3, vel.y, vel.z * 0.3);
			return;
		}

		if (!currentlyMiningBlock) {
			Vec3 lookPoint = new Vec3(destPos.x, player.getEyeY(), destPos.z);
			rotateTowards(player, lookPoint, MAX_YAW_STEP_DEG, MAX_PITCH_STEP_DEG);
			mc.options.keyUp.setDown(true);
		} else {
			// Gently nudge toward column center without interfering with downward mining pitch
			mc.options.keyUp.setDown(false);
		}
	}

	private static void applySpeedMultiplier(LocalPlayer player, double configuredMultiplier, double stageFactor) {
		if (player == null || !player.getAbilities().flying) {
			return;
		}
		float baseFlySpeed = 0.05f;
		float clamped = (float) Mth.clamp(baseFlySpeed * configuredMultiplier * stageFactor, 0.02, 0.12);
		player.getAbilities().setFlyingSpeed(clamped);
	}
}