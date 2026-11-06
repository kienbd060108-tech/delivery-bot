package com.example.delivery.navigation;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.chat.ChatMonitor;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.model.CustomerTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Manages the {@code /rtp} loop (Step 5) until the player is in the customer's world and within
 * {@code config.maxRtpDistance} (default: {@code 5,000} blocks) horizontally:
 * <pre>
 *   distance = sqrt((playerX - customerX)^2 + (playerZ - customerZ)^2)
 * </pre>
 *
 * <p>Key reliability guarantees:</p>
 * <ul>
 *   <li>Never sends multiple {@code /rtp} commands simultaneously.</li>
 *   <li>Keeps the player completely still during the 5-second {@code [RTP] Sẽ dịch chuyển sau 5 giây! Vui lòng không di chuyển!} warmup.</li>
 *   <li>Waits for server teleport confirmation and chunk/position stabilization before reading new coordinates.</li>
 *   <li>Enforces configurable cooldowns and a configurable maximum RTP attempt limit.</li>
 * </ul>
 */
public final class RtpController {
	public enum Phase {
		IDLE,
		WAITING_COOLDOWN,
		WAITING_FOR_TELEPORT,
		STABILIZING_POSITION
	}

	public enum TickOutcome {
		WITHIN_RANGE_READY_FOR_FLIGHT,
		IN_PROGRESS,
		MAX_ATTEMPTS_EXCEEDED
	}

	private static final int WARMUP_AND_TELEPORT_TIMEOUT_TICKS = 240; // 12 seconds
	private static final int STABILIZE_TICKS = 30; // 1.5 seconds after teleport
	private Phase phase = Phase.IDLE;
	private int attempts = 0;
	private int phaseTicks = 0;
	private int cooldownRemainingTicks = 0;
	private Vec3 preRtpPosition = null;
	private Vec3 pickupWorldPosition = null;

	public void resetForNewOrder(Vec3 currentPickupPos) {
		this.phase = Phase.IDLE;
		this.attempts = 0;
		this.phaseTicks = 0;
		this.cooldownRemainingTicks = 0;
		this.preRtpPosition = null;
		this.pickupWorldPosition = currentPickupPos;
	}

	/**
	 * Calculates the exact horizontal distance on the XZ plane:
	 * {@code sqrt((playerX - customerX)^2 + (playerZ - customerZ)^2)}.
	 */
	public static double calculateHorizontalDistance(double playerX, double playerZ, int customerX, int customerZ) {
		double dx = playerX - customerX;
		double dz = playerZ - customerZ;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Determines whether the player is already in the delivery world and within {@code maxRtpDistance} blocks.
	 */
	public boolean isWithinAcceptableDistance(Minecraft mc, CustomerTarget target, DeliveryConfig config) {
		if (mc == null || mc.player == null || target == null) {
			return false;
		}
		// If the server action bar still says "Đến thế giới world để giao hàng", we are still in the /warp tuban world!
		if (ChatMonitor.getInstance().isActionBarSaysDifferentWorld()) {
			return false;
		}
		LocalPlayer player = mc.player;
		// Also if we haven't done any RTP yet and are still standing right inside the /warp tuban bakery area,
		// we must RTP into the survival world first unless the customer is literally right nearby (< 80 blocks).
		double horizDist = calculateHorizontalDistance(player.getX(), player.getZ(), target.x(), target.z());
		if (this.attempts == 0 && this.pickupWorldPosition != null) {
			double distFromBakery = player.position().distanceTo(this.pickupWorldPosition);
			if (distFromBakery < 40.0 && horizDist > 80.0) {
				return false;
			}
		}
		return horizDist <= config.maxRtpDistance;
	}

	public TickOutcome tick(Minecraft mc, CustomerTarget target, DeliveryConfig config) {
		if (mc == null || mc.player == null || target == null) {
			return TickOutcome.IN_PROGRESS;
		}
		LocalPlayer player = mc.player;

		// Always keep movement keys released during RTP evaluation & warmup
		FlightNavigator.releaseAllMovementKeys(mc);

		switch (this.phase) {
			case IDLE -> {
				if (isWithinAcceptableDistance(mc, target, config)) {
					return TickOutcome.WITHIN_RANGE_READY_FOR_FLIGHT;
				}
				if (this.attempts >= config.maxRtpAttempts) {
					return TickOutcome.MAX_ATTEMPTS_EXCEEDED;
				}
				sendSingleRtpCommand(mc);
				return TickOutcome.IN_PROGRESS;
			}

			case WAITING_COOLDOWN -> {
				this.phaseTicks++;
				if (this.cooldownRemainingTicks > 0) {
					this.cooldownRemainingTicks--;
					return TickOutcome.IN_PROGRESS;
				}
				if (isWithinAcceptableDistance(mc, target, config)) {
					this.phase = Phase.IDLE;
					return TickOutcome.WITHIN_RANGE_READY_FOR_FLIGHT;
				}
				if (this.attempts >= config.maxRtpAttempts) {
					return TickOutcome.MAX_ATTEMPTS_EXCEEDED;
				}
				sendSingleRtpCommand(mc);
				return TickOutcome.IN_PROGRESS;
			}

			case WAITING_FOR_TELEPORT -> {
				this.phaseTicks++;
				ChatMonitor chat = ChatMonitor.getInstance();

				boolean positionJumped = this.preRtpPosition != null
					&& player.position().distanceTo(this.preRtpPosition) > 45.0;

				if (chat.isRtpCompleted() || positionJumped) {
					DeliveryBotMod.LOGGER.info(
						"[RtpController] Teleport detected on attempt #{} (chat={}, posJump={})",
						this.attempts, chat.isRtpCompleted(), positionJumped
					);
					this.phase = Phase.STABILIZING_POSITION;
					this.phaseTicks = 0;
					return TickOutcome.IN_PROGRESS;
				}

				if (chat.isRtpFailedOrCancelled()) {
					DeliveryBotMod.LOGGER.warn("[RtpController] Server reported RTP cooldown/cancel; waiting before retry");
					this.phase = Phase.WAITING_COOLDOWN;
					this.phaseTicks = 0;
					this.cooldownRemainingTicks = Math.max(60, config.rtpCooldownTicks);
					return TickOutcome.IN_PROGRESS;
				}

				if (this.phaseTicks >= WARMUP_AND_TELEPORT_TIMEOUT_TICKS) {
					DeliveryBotMod.LOGGER.warn("[RtpController] RTP attempt #{} timed out after {} ticks", this.attempts, this.phaseTicks);
					this.phase = Phase.WAITING_COOLDOWN;
					this.phaseTicks = 0;
					this.cooldownRemainingTicks = Math.max(40, config.rtpCooldownTicks / 2);
				}
				return TickOutcome.IN_PROGRESS;
			}

			case STABILIZING_POSITION -> {
				this.phaseTicks++;
				if (this.phaseTicks < STABILIZE_TICKS) {
					return TickOutcome.IN_PROGRESS;
				}
				double newDist = calculateHorizontalDistance(player.getX(), player.getZ(), target.x(), target.z());
				DeliveryBotMod.LOGGER.info(
					"[RtpController] Post-RTP position stabilized at ({}, {}, {}) -> Horizontal distance to {}: {:.1f}m (limit: {:.1f}m)",
					(int) player.getX(), (int) player.getY(), (int) player.getZ(),
					target.customerName(), newDist, config.maxRtpDistance
				);

				if (isWithinAcceptableDistance(mc, target, config)) {
					this.phase = Phase.IDLE;
					return TickOutcome.WITHIN_RANGE_READY_FOR_FLIGHT;
				}

				if (this.attempts >= config.maxRtpAttempts) {
					return TickOutcome.MAX_ATTEMPTS_EXCEEDED;
				}

				this.phase = Phase.WAITING_COOLDOWN;
				this.phaseTicks = 0;
				this.cooldownRemainingTicks = config.rtpCooldownTicks;
				return TickOutcome.IN_PROGRESS;
			}
		}

		return TickOutcome.IN_PROGRESS;
	}

	private void sendSingleRtpCommand(Minecraft mc) {
		if (mc == null || mc.player == null || mc.player.connection == null) {
			return;
		}
		FlightNavigator.releaseAllMovementKeys(mc);
		ChatMonitor.getInstance().resetRtpAttemptFlags();
		this.preRtpPosition = mc.player.position();
		this.attempts++;
		this.phase = Phase.WAITING_FOR_TELEPORT;
		this.phaseTicks = 0;

		DeliveryBotMod.LOGGER.info("[RtpController] Sending /rtp (attempt {} / {})", this.attempts, DeliveryConfig.get().maxRtpAttempts);
		mc.player.connection.sendCommand("rtp");
	}

	public int getAttempts() {
		return attempts;
	}

	public Phase getPhase() {
		return phase;
	}

	public int getCooldownRemainingTicks() {
		return cooldownRemainingTicks;
	}
}