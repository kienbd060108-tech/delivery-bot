package com.example.delivery.interaction;

import com.example.delivery.model.CustomerTarget;
import com.example.delivery.util.TextUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Detects pickup NPCs ({@code TIỆM BÁNH}) and dynamically named customer NPCs
 * (e.g. {@code Chị Tư (Khách hàng)}) in the client world and executes standard entity interactions.
 */
public final class NpcDetector {
	public static final String BAKERY_NPC_NAME = "TIỆM BÁNH";

	private NpcDetector() {
	}

	/**
	 * Finds the {@code TIỆM BÁNH} pickup NPC near the player.
	 *
	 * <p>In Video 1 @ 8.5s, {@code TIỆM BÁNH} is a {@link Villager} named {@code 🥐 TIỆM BÁNH}.
	 * If a server also places a floating hologram {@link ArmorStand} or {@link Display} above the NPC,
	 * this method resolves the hologram to the underlying interactable {@link Villager}/{@link LivingEntity}.</p>
	 */
	public static Entity findBakeryNpc(Minecraft mc) {
		if (mc == null || mc.level == null || mc.player == null) {
			return null;
		}
		LocalPlayer player = mc.player;
		Entity bestDirectNpc = null;
		double bestDirectDistSq = Double.MAX_VALUE;

		Entity bestHologram = null;
		double bestHoloDistSq = Double.MAX_VALUE;

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == null || entity == player || !entity.isAlive()) {
				continue;
			}
			String name = extractEntityName(entity);
			if (!isBakeryNpcName(name)) {
				continue;
			}
			double distSq = player.distanceToSqr(entity);
			if (isDirectInteractableNpc(entity)) {
				// Strongly prefer Villager instances
				double effectiveDistSq = (entity instanceof Villager) ? distSq * 0.5 : distSq;
				if (effectiveDistSq < bestDirectDistSq) {
					bestDirectDistSq = effectiveDistSq;
					bestDirectNpc = entity;
				}
			} else if (distSq < bestHoloDistSq) {
				bestHoloDistSq = distSq;
				bestHologram = entity;
			}
		}

		if (bestDirectNpc != null) {
			return bestDirectNpc;
		}
		if (bestHologram != null) {
			Entity resolved = resolveNpcNearHologram(mc, bestHologram);
			return resolved != null ? resolved : bestHologram;
		}
		return null;
	}

	/**
	 * Finds the customer NPC near the target coordinates matching the customer name extracted from chat.
	 *
	 * <p>In Video 2 @ 6.0s, the customer is a {@link Villager} named {@code Chị Tư (Khách hàng)}.</p>
	 */
	public static Entity findCustomerNpc(Minecraft mc, CustomerTarget target) {
		if (mc == null || mc.level == null || mc.player == null || target == null) {
			return null;
		}
		LocalPlayer player = mc.player;
		Vec3 targetPos = target.centerPos();

		Entity bestExactNpc = null;
		double bestExactScore = Double.MAX_VALUE;

		Entity bestExactHologram = null;
		double bestHoloScore = Double.MAX_VALUE;

		Entity bestFallbackCustomerTagNpc = null;
		double bestFallbackScore = Double.MAX_VALUE;

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == null || entity == player || !entity.isAlive()) {
				continue;
			}
			String name = extractEntityName(entity);
			if (name.isEmpty() || isBakeryNpcName(name)) {
				continue;
			}

			double distToTarget = entity.position().distanceTo(targetPos);
			double distToPlayer = player.distanceTo(entity);
			if (distToTarget > 64.0 && distToPlayer > 64.0) {
				continue;
			}

			boolean exactCustomerMatch = isCustomerNameMatch(name, target.customerName());
			boolean genericCustomerTag = TextUtils.containsIgnoreCase(name, "Khách hàng") && distToTarget <= 12.0;

			if (exactCustomerMatch) {
				double score = distToTarget + (entity instanceof Villager ? -10.0 : 0.0);
				if (isDirectInteractableNpc(entity)) {
					if (score < bestExactScore) {
						bestExactScore = score;
						bestExactNpc = entity;
					}
				} else if (score < bestHoloScore) {
					bestHoloScore = score;
					bestExactHologram = entity;
				}
			} else if (genericCustomerTag && isDirectInteractableNpc(entity)) {
				if (distToTarget < bestFallbackScore) {
					bestFallbackScore = distToTarget;
					bestFallbackCustomerTagNpc = entity;
				}
			}
		}

		if (bestExactNpc != null) {
			return bestExactNpc;
		}
		if (bestHologram != null) {
			Entity resolved = resolveNpcNearHologram(mc, bestHologram);
			return resolved != null ? resolved : bestHologram;
		}
		return bestFallbackCustomerTagNpc;
	}

	/**
	 * Checks whether an entity name matches the pickup NPC ({@code TIỆM BÁNH}) and is not a customer.
	 */
	public static boolean isBakeryNpcName(String rawName) {
		if (rawName == null || rawName.isBlank()) {
			return false;
		}
		String lower = TextUtils.normalizeLower(rawName);
		if (lower.contains("khách hàng")) {
			return false;
		}
		return lower.contains("tiệm bánh");
	}

	/**
	 * Checks whether an entity name matches the target customer name (e.g. {@code "Chị Tư"} matching
	 * {@code "Chị Tư (Khách hàng)"}). Never matches {@code TIỆM BÁNH}.
	 */
	public static boolean isCustomerNameMatch(String rawEntityName, String targetCustomerName) {
		if (rawEntityName == null || rawEntityName.isBlank()
			|| targetCustomerName == null || targetCustomerName.isBlank()) {
			return false;
		}
		if (isBakeryNpcName(rawEntityName)) {
			return false;
		}
		String entityLower = TextUtils.normalizeLower(rawEntityName);
		String customerLower = TextUtils.normalizeLower(targetCustomerName);
		if (entityLower.equals(customerLower)
			|| entityLower.startsWith(customerLower + " ")
			|| entityLower.contains(customerLower + " (khách hàng)")) {
			return true;
		}
		return entityLower.contains(customerLower) && entityLower.contains("khách");
	}

	private static boolean isDirectInteractableNpc(Entity entity) {
		if (entity instanceof ArmorStand || entity instanceof Display) {
			return false;
		}
		return entity instanceof LivingEntity || entity.isPickable();
	}

	private static Entity resolveNpcNearHologram(Minecraft mc, Entity hologram) {
		Entity best = null;
		double bestScore = Double.MAX_VALUE;
		for (Entity candidate : mc.level.entitiesForRendering()) {
			if (candidate == null || candidate == mc.player || candidate == hologram || !candidate.isAlive()) {
				continue;
			}
			if (!isDirectInteractableNpc(candidate)) {
				continue;
			}
			double dx = candidate.getX() - hologram.getX();
			double dz = candidate.getZ() - hologram.getZ();
			double horizDist = Math.sqrt(dx * dx + dz * dz);
			double vertDist = Math.abs(candidate.getY() - hologram.getY());
			if (horizDist <= 2.5 && vertDist <= 3.8) {
				double score = horizDist + (candidate instanceof Villager ? -5.0 : 0.0);
				if (score < bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
		}
		return best;
	}

	public static String extractEntityName(Entity entity) {
		if (entity == null) {
			return "";
		}
		Component custom = entity.getCustomName();
		if (custom != null) {
			String s = TextUtils.stripFormatting(custom);
			if (!s.isEmpty()) {
				return s;
			}
		}
		Component display = entity.getDisplayName();
		if (display != null) {
			String s = TextUtils.stripFormatting(display);
			if (!s.isEmpty()) {
				return s;
			}
		}
		return "";
	}

	/**
	 * Performs a standard right-click entity interaction using Minecraft 26.2's {@code MultiPlayerGameMode#interact}.
	 */
	public static boolean interactWithNpc(Minecraft mc, Entity npc) {
		if (mc == null || mc.player == null || mc.gameMode == null || npc == null || !npc.isAlive()) {
			return false;
		}
		Vec3 hitLocation = npc.getBoundingBox().getCenter();
		EntityHitResult hitResult = new EntityHitResult(npc, hitLocation);
		InteractionResult result = mc.gameMode.interact(mc.player, npc, hitResult, InteractionHand.MAIN_HAND);
		mc.player.swing(InteractionHand.MAIN_HAND);
		return result != InteractionResult.FAIL;
	}
}