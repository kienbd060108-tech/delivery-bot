package com.example.delivery.navigation;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import com.example.delivery.DeliveryBotMod;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.model.CustomerTarget;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Integrates the Baritone API ({@code baritone-api-fabric-1.19.0.jar}) for fully automatic
 * ground/terrain navigation and automatic block breaking along the path without flying up and falling down.
 */
public final class BaritoneNavigator {
	private static Goal lastIssuedGoal = null;
	private static int repathCooldownTicks = 0;

	private BaritoneNavigator() {
	}

	/**
	 * Returns true if the Baritone API provider is loaded and ready at runtime.
	 */
	public static boolean isBaritoneAvailable() {
		try {
			return BaritoneAPI.getProvider() != null && BaritoneAPI.getProvider().getPrimaryBaritone() != null;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Retrieves the primary {@link IBaritone} instance, or {@code null} if unavailable.
	 */
	public static IBaritone getBaritone() {
		try {
			return BaritoneAPI.getProvider().getPrimaryBaritone();
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Configures Baritone settings so the bot automatically breaks obstructing blocks, places blocks
	 * (bridging/pillaring) when needed, selects the right tool/block automatically, sprints when possible,
	 * and never wastes Obsidian.
	 */
	public static void configureForDeliveryNavigation(DeliveryConfig config) {
		try {
			Settings settings = BaritoneAPI.getSettings();
			if (settings == null) {
				return;
			}
			boolean allowPlace = config == null || config.baritoneAllowPlace;
			settings.allowBreak.value = config == null || config.baritoneAllowBreak;
			settings.allowPlace.value = allowPlace;
			settings.allowParkourPlace.value = allowPlace;
			settings.allowInventory.value = true;
			settings.autoTool.value = true;
			settings.allowSprint.value = config == null || config.baritoneAllowSprint;
			settings.allowParkour.value = true;
			settings.allowDownward.value = true;
			settings.freeLook.value = false;

			if (settings.acceptableThrowawayItems != null && settings.acceptableThrowawayItems.value != null) {
				var throwaway = settings.acceptableThrowawayItems.value;
				if (!throwaway.contains(Items.DIRT)) {
					throwaway.add(Items.DIRT);
				}
				if (!throwaway.contains(Items.COBBLESTONE)) {
					throwaway.add(Items.COBBLESTONE);
				}
				if (!throwaway.contains(Items.COBBLED_DEEPSLATE)) {
					throwaway.add(Items.COBBLED_DEEPSLATE);
				}
				if (!throwaway.contains(Items.NETHERRACK)) {
					throwaway.add(Items.NETHERRACK);
				}
				if (!throwaway.contains(Items.STONE)) {
					throwaway.add(Items.STONE);
				}
				throwaway.remove(Items.OBSIDIAN);
			}
		} catch (Throwable t) {
			DeliveryBotMod.LOGGER.warn("[BaritoneNavigator] Failed to configure Baritone settings", t);
		}
	}

	/**
	 * Cancels all active Baritone pathing processes and clears any forced movement keys.
	 */
	public static void stopBaritone(Minecraft mc) {
		lastIssuedGoal = null;
		repathCooldownTicks = 0;
		try {
			IBaritone baritone = getBaritone();
			if (baritone != null) {
				baritone.getPathingBehavior().cancelEverything();
				baritone.getInputOverrideHandler().clearAllKeys();
			}
		} catch (Throwable ignored) {
		}
		FlightNavigator.releaseAllMovementKeys(mc);
	}

	/**
	 * Uses Baritone to automatically navigate to an NPC entity (such as {@code TIỆM BÁNH} or the customer)
	 * and aims at the NPC once within {@code interactionRange}.
	 *
	 * @return true once within {@code interactionRange} and aimed at {@code npc}.
	 */
	public static boolean navigateToNpcTick(Minecraft mc, Entity npc, double interactionRange, DeliveryConfig config) {
		if (mc == null || mc.player == null || npc == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		Vec3 npcCenter = npc.getBoundingBox().getCenter();
		double dist = player.getEyePosition().distanceTo(npcCenter);

		if (dist <= interactionRange) {
			stopBaritone(mc);
			double angleRem = FlightNavigator.rotateTowards(player, npcCenter, 28.0f, 20.0f);
			return angleRem <= 12.0;
		}

		IBaritone baritone = getBaritone();
		if (baritone == null) {
			return FlightNavigator.approachNpcOnGround(mc, npc, interactionRange);
		}

		configureForDeliveryNavigation(config);
		int goalRadius = Math.max(1, (int) Math.floor(interactionRange - 0.5));
		Goal goal = new GoalNear(npc.blockPosition(), goalRadius);
		ensureGoalActive(baritone, goal);
		return false;
	}

	/**
	 * Uses Baritone to automatically travel to the calculated Nether coordinates {@code (X / 8, Z / 8)}.
	 *
	 * @return true once the player reaches {@code (netherX, netherZ)}.
	 */
	public static boolean navigateToNetherTargetTick(Minecraft mc, CustomerTarget target, DeliveryConfig config) {
		if (mc == null || mc.player == null || target == null) {
			return false;
		}
		return navigateToNetherCoordsTick(mc, target.netherX(), target.netherZ(), config);
	}

	/**
	 * Uses Baritone to automatically travel to the specified Nether coordinates {@code (targetNetherX, targetNetherZ)}.
	 *
	 * @return true once the player reaches {@code (targetNetherX, targetNetherZ)}.
	 */
	public static boolean navigateToNetherCoordsTick(
		Minecraft mc,
		int targetNetherX,
		int targetNetherZ,
		DeliveryConfig config
	) {
		if (mc == null || mc.player == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		double horizDist = Math.hypot((targetNetherX + 0.5) - player.getX(), (targetNetherZ + 0.5) - player.getZ());

		if (horizDist <= 1.25) {
			stopBaritone(mc);
			return true;
		}

		IBaritone baritone = getBaritone();
		if (baritone == null) {
			// Fallback ground walk if Baritone is not loaded
			Vec3 dest = new Vec3(targetNetherX + 0.5, player.getEyeY(), targetNetherZ + 0.5);
			FlightNavigator.rotateTowards(player, dest, 25.0f, 16.0f);
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(horizDist > 4.0);
			return false;
		}

		configureForDeliveryNavigation(config);
		Goal goal = (horizDist > 12.0)
			? new GoalXZ(targetNetherX, targetNetherZ)
			: new GoalBlock(targetNetherX, (int) Math.round(player.getY()), targetNetherZ);
		ensureGoalActive(baritone, goal);
		return false;
	}

	/**
	 * Uses Baritone to automatically navigate to the customer's exact Overworld coordinates
	 * and break any blocks obstructing its path.
	 *
	 * @return true once the player is within interaction range of the customer or exact target coordinates.
	 */
	public static boolean navigateToCustomerInOverworldTick(
		Minecraft mc,
		CustomerTarget target,
		Entity detectedCustomer,
		DeliveryConfig config
	) {
		if (mc == null || mc.player == null || target == null) {
			return false;
		}
		LocalPlayer player = mc.player;

		if (player.getAbilities().flying) {
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}

		if (detectedCustomer != null) {
			double dist3D = player.getEyePosition().distanceTo(detectedCustomer.getBoundingBox().getCenter());
			if (dist3D <= config.customerInteractionDistance) {
				stopBaritone(mc);
				return true;
			}
		}

		double horizDist = target.horizontalDistanceTo(player.getX(), player.getZ());
		double vertDist = Math.abs(player.getY() - target.y());
		if (detectedCustomer == null && horizDist <= 2.0 && vertDist <= 2.5) {
			stopBaritone(mc);
			return true;
		}

		IBaritone baritone = getBaritone();
		if (baritone == null) {
			if (detectedCustomer != null) {
				return FlightNavigator.approachNpcOnGround(mc, detectedCustomer, config.customerInteractionDistance);
			}
			Vec3 dest = target.centerPos();
			FlightNavigator.rotateTowards(player, dest, 25.0f, 16.0f);
			mc.options.keyUp.setDown(true);
			mc.options.keySprint.setDown(horizDist > 5.0);
			mc.options.keyJump.setDown(player.horizontalCollision && player.onGround());
			return false;
		}

		configureForDeliveryNavigation(config);

		BlockPos goalBlockPos = (detectedCustomer != null)
			? detectedCustomer.blockPosition()
			: new BlockPos(target.x(), target.y(), target.z());
		int radius = Math.max(1, (int) Math.floor(config.customerInteractionDistance - 0.5));
		Goal goal = new GoalNear(goalBlockPos, radius);
		ensureGoalActive(baritone, goal);
		return false;
	}

	/**
	 * Returns a concise status string describing Baritone's current pathfinding state for the UI.
	 */
	public static String getBaritoneStatusSummary() {
		try {
			IBaritone baritone = getBaritone();
			if (baritone == null) {
				return "Fallback Navigator";
			}
			IPathingBehavior pathing = baritone.getPathingBehavior();
			if (pathing.isPathing()) {
				Optional<Double> etaTicks = pathing.estimatedTicksToGoal();
				if (etaTicks.isPresent() && etaTicks.get() < 100000) {
					double seconds = etaTicks.get() / 20.0;
					return String.format("Baritone Pathing (ETA %.0fs)", seconds);
				}
				return "Baritone Pathing Active";
			}
			if (pathing.getInProgress().isPresent()) {
				return "Baritone Calculating Path...";
			}
			return "Baritone Ready";
		} catch (Throwable t) {
			return "Baritone Standby";
		}
	}

	private static void ensureGoalActive(IBaritone baritone, Goal desiredGoal) {
		if (repathCooldownTicks > 0) {
			repathCooldownTicks--;
		}
		ICustomGoalProcess customGoalProcess = baritone.getCustomGoalProcess();
		IPathingBehavior pathingBehavior = baritone.getPathingBehavior();

		boolean sameGoal = desiredGoal.equals(lastIssuedGoal) && desiredGoal.equals(pathingBehavior.getGoal());
		boolean activelyWorking = pathingBehavior.isPathing()
			|| pathingBehavior.getInProgress().isPresent()
			|| customGoalProcess.isActive();

		if (!sameGoal || (!activelyWorking && repathCooldownTicks == 0)) {
			lastIssuedGoal = desiredGoal;
			repathCooldownTicks = 15;
			customGoalProcess.setGoalAndPath(desiredGoal);
			DeliveryBotMod.LOGGER.info("[BaritoneNavigator] Issued Baritone goal: {}", desiredGoal);
		}
	}
}