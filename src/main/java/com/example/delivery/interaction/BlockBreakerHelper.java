package com.example.delivery.interaction;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.model.CustomerTarget;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.navigation.FlightNavigator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Detects blocks obstructing the player's vertical descent (or line-of-sight to the customer),
 * selects and equips the optimal mining tool (Pickaxe, Axe, Shovel, Hoe, Shears, Sword) from
 * the hotbar/inventory based on {@link ItemStack#getDestroySpeed(BlockState)} and
 * {@link ItemStack#isCorrectToolForDrops(BlockState)}, and breaks the block using standard
 * client block-breaking calls.
 */
public final class BlockBreakerHelper {
	private static final double MAX_MINING_REACH = 4.5;

	private BlockPos currentMiningPos = null;
	private int miningTicks = 0;

	/**
	 * Finds the highest-priority obstructing block that is preventing the player from descending
	 * straight down to the customer's Y level (or blocking direct access to the customer).
	 *
	 * <p>Never returns blocks at or below {@code customerFloorY} so the ground the customer is standing
	 * on is preserved.</p>
	 */
	public BlockPos findObstructingBlockDuringDescent(
		Minecraft mc,
		CustomerTarget target,
		Entity customerEntity
	) {
		if (mc == null || mc.level == null || mc.player == null || target == null) {
			return null;
		}
		LocalPlayer player = mc.player;
		int customerY = customerEntity != null
			? (int) Math.floor(customerEntity.getY())
			: target.y();
		// Never break the floor block at customerY - 1 that the customer stands on
		int minBreakableY = customerY;

		Vec3 eyes = player.getEyePosition();

		// 1. Check blocks overlapping the player's head or feet (e.g. leaves, cobwebs, snow, trapdoors)
		BlockPos headPos = BlockPos.containing(player.getX(), player.getY() + 1.0, player.getZ());
		if (headPos.getY() >= minBreakableY && isBreakableObstruction(mc, headPos)) {
			return headPos;
		}

		BlockPos feetPos = BlockPos.containing(player.getX(), player.getY() + 0.1, player.getZ());
		if (feetPos.getY() >= minBreakableY && isBreakableObstruction(mc, feetPos)) {
			return feetPos;
		}

		// 2. If the player is still above the customer's Y level, check blocks directly below the player's
		// bounding box that are stopping or about to stop vertical descent
		if (player.getY() > customerY + 1.1) {
			// Check the primary column directly under the player's center first (1 to 3 blocks below feet)
			for (int dy = 1; dy <= 3; dy++) {
				int checkY = (int) Math.floor(player.getY() - 0.05) - dy + 1;
				if (checkY < minBreakableY) {
					break;
				}
				BlockPos centerBelow = BlockPos.containing(player.getX(), checkY, player.getZ());
				if (eyes.distanceTo(Vec3.atCenterOf(centerBelow)) <= MAX_MINING_REACH
					&& isBreakableObstruction(mc, centerBelow)) {
					return centerBelow;
				}
			}

			// Also check all corners of the player's hitbox just below the feet in case the player's edge
			// is resting on a ledge/branch while descending
			AABB boxBelow = player.getBoundingBox().deflate(0.02).move(0.0, -0.35, 0.0);
			int minX = (int) Math.floor(boxBelow.minX);
			int maxX = (int) Math.floor(boxBelow.maxX);
			int minZ = (int) Math.floor(boxBelow.minZ);
			int maxZ = (int) Math.floor(boxBelow.maxZ);
			int supportY = (int) Math.floor(boxBelow.minY);

			if (supportY >= minBreakableY) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) {
						BlockPos cornerPos = new BlockPos(x, supportY, z);
						if (eyes.distanceTo(Vec3.atCenterOf(cornerPos)) <= MAX_MINING_REACH
							&& isBreakableObstruction(mc, cornerPos)) {
							return cornerPos;
						}
					}
				}
			}
		}

		// 3. Check if a solid block directly obstructs line-of-sight between the player and the customer when close
		Vec3 destCenter = customerEntity != null
			? customerEntity.getBoundingBox().getCenter()
			: new Vec3(target.x() + 0.5, target.y() + 1.0, target.z() + 0.5);
		if (eyes.distanceTo(destCenter) <= 5.5) {
			BlockHitResult hit = mc.level.clip(new ClipContext(
				eyes,
				destCenter,
				ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE,
				player
			));
			if (hit.getType() == HitResult.Type.BLOCK) {
				BlockPos hitPos = hit.getBlockPos();
				if (hitPos.getY() >= minBreakableY
					&& eyes.distanceTo(Vec3.atCenterOf(hitPos)) <= MAX_MINING_REACH
					&& isBreakableObstruction(mc, hitPos)) {
					return hitPos;
				}
			}
		}

		return null;
	}

	/**
	 * Returns true if the block at {@code pos} is a non-air, non-liquid, breakable block that
	 * obstructs player movement (either has a collision shape or is cobweb/powder snow/leaves).
	 */
	public static boolean isBreakableObstruction(Minecraft mc, BlockPos pos) {
		if (mc == null || mc.level == null || pos == null) {
			return false;
		}
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || state.liquid()) {
			return false;
		}
		float hardness = state.getDestroySpeed(mc.level, pos);
		if (hardness < 0.0f) {
			// Unbreakable block (e.g., bedrock, barrier)
			return false;
		}
		if (state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SCAFFOLDING)) {
			return true;
		}
		return !state.getCollisionShape(mc.level, pos).isEmpty();
	}

	/**
	 * Scores an {@link ItemStack} for breaking the given {@link BlockState}.
	 * Higher scores indicate a better tool (e.g. Pickaxe for stone/ice, Shovel for snow/dirt, Axe for wood,
	 * Shears/Sword/Hoe for leaves/cobwebs).
	 */
	public static float scoreToolForBlock(ItemStack stack, BlockState state) {
		if (stack == null || stack.isEmpty() || state == null) {
			return 0.0f;
		}
		// Leave 1 durability safety margin on damageable tools
		if (stack.isDamageableItem() && stack.getMaxDamage() > 1) {
			if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
				return 0.0f;
			}
		}
		float destroySpeed = stack.getDestroySpeed(state);
		boolean correctTool = stack.isCorrectToolForDrops(state);
		if (destroySpeed <= 1.0f && !correctTool) {
			return 1.0f;
		}
		return destroySpeed + (correctTool ? 100.0f : 0.0f);
	}

	/**
	 * Selects the most appropriate tool from the player's hotbar or main inventory for breaking {@code state}.
	 * Never selects the delivery package ({@code Gói hàng [...]}) to mine blocks.
	 */
	public static void equipBestToolForBlock(Minecraft mc, BlockState state, DeliveryOrder activeOrder) {
		if (mc == null || mc.player == null || state == null) {
			return;
		}
		LocalPlayer player = mc.player;
		Inventory inv = player.getInventory();

		int bestSlot = -1;
		float bestScore = 1.0f;

		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			List<String> lines = GuiInventoryHelper.extractItemTextLines(mc, stack);
			if (GuiInventoryHelper.matchesDeliveryPackage(lines, activeOrder)
				|| GuiInventoryHelper.isAnyDeliveryPackage(lines)) {
				continue;
			}
			float score = scoreToolForBlock(stack, state);
			// Slightly prefer tools already in the hotbar on tie
			if (i < 9 && score > 1.0f) {
				score += 0.05f;
			}
			if (score > bestScore) {
				bestScore = score;
				bestSlot = i;
			}
		}

		if (bestSlot != -1) {
			if (bestSlot < 9) {
				if (inv.getSelectedSlot() != bestSlot) {
					inv.setSelectedSlot(bestSlot);
				}
				return;
			}
			// Best tool is in main inventory (9..35): swap it into a hotbar slot (avoiding the package slot)
			if (mc.gameMode != null && player.inventoryMenu != null) {
				int targetHotbar = chooseHotbarSlotForTool(mc, inv, activeOrder);
				mc.gameMode.handleContainerInput(
					player.inventoryMenu.containerId,
					bestSlot,
					targetHotbar,
					ContainerInput.SWAP,
					player
				);
				inv.setSelectedSlot(targetHotbar);
				return;
			}
		}

		// If no specialized tool is required/available, ensure we are not holding the delivery package while mining
		ItemStack currentHeld = player.getMainHandItem();
		if (!currentHeld.isEmpty()) {
			List<String> heldLines = GuiInventoryHelper.extractItemTextLines(mc, currentHeld);
			if (GuiInventoryHelper.matchesDeliveryPackage(heldLines, activeOrder)
				|| GuiInventoryHelper.isAnyDeliveryPackage(heldLines)) {
				int safeHotbar = chooseHotbarSlotForTool(mc, inv, activeOrder);
				inv.setSelectedSlot(safeHotbar);
			}
		}
	}

	private static int chooseHotbarSlotForTool(Minecraft mc, Inventory inv, DeliveryOrder activeOrder) {
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		for (int i = 0; i < 9; i++) {
			List<String> lines = GuiInventoryHelper.extractItemTextLines(mc, inv.getItem(i));
			if (!GuiInventoryHelper.matchesDeliveryPackage(lines, activeOrder)
				&& !GuiInventoryHelper.isAnyDeliveryPackage(lines)) {
				return i;
			}
		}
		return 0;
	}

	/**
	 * Equips the appropriate tool, aims at {@code pos}, and mines the block for one tick.
	 *
	 * @return true if the block is still being broken, or false once the block is broken or out of range.
	 */
	public boolean breakBlockTick(Minecraft mc, BlockPos pos, DeliveryOrder activeOrder) {
		if (mc == null || mc.level == null || mc.player == null || mc.gameMode == null || pos == null) {
			stopBreaking(mc);
			return false;
		}
		if (!isBreakableObstruction(mc, pos)) {
			stopBreaking(mc);
			return false;
		}

		Vec3 targetCenter = Vec3.atCenterOf(pos);
		if (mc.player.getEyePosition().distanceTo(targetCenter) > MAX_MINING_REACH + 0.5) {
			stopBreaking(mc);
			return false;
		}

		BlockState state = mc.level.getBlockState(pos);
		equipBestToolForBlock(mc, state, activeOrder);

		// Aim toward the block being broken
		FlightNavigator.rotateTowards(mc.player, targetCenter, 35.0f, 30.0f);

		Direction face = getBestMiningDirection(mc.player, pos);

		if (!pos.equals(this.currentMiningPos)) {
			this.currentMiningPos = pos.immutable();
			this.miningTicks = 0;
			DeliveryBotMod.LOGGER.info(
				"[BlockBreaker] Starting to break obstructing block {} at ({}, {}, {}) with {}",
				state.getBlock().getDescriptionId(),
				pos.getX(), pos.getY(), pos.getZ(),
				mc.player.getMainHandItem().isEmpty() ? "Hand" : mc.player.getMainHandItem().getHoverName().getString()
			);
			mc.gameMode.startDestroyBlock(this.currentMiningPos, face);
			mc.player.swing(InteractionHand.MAIN_HAND);
		} else {
			this.miningTicks++;
			mc.gameMode.continueDestroyBlock(this.currentMiningPos, face);
			mc.player.swing(InteractionHand.MAIN_HAND);
		}

		return !mc.level.getBlockState(pos).isAir();
	}

	public void stopBreaking(Minecraft mc) {
		if (this.currentMiningPos != null) {
			if (mc != null && mc.gameMode != null) {
				mc.gameMode.stopDestroyBlock();
			}
			this.currentMiningPos = null;
			this.miningTicks = 0;
		}
	}

	private static Direction getBestMiningDirection(LocalPlayer player, BlockPos pos) {
		Vec3 eyes = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		double dy = eyes.y - center.y;
		double dx = eyes.x - center.x;
		double dz = eyes.z - center.z;
		if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) {
			return dy >= 0 ? Direction.UP : Direction.DOWN;
		}
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx >= 0 ? Direction.EAST : Direction.WEST;
		}
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	public BlockPos getCurrentMiningPos() {
		return currentMiningPos;
	}

	public int getMiningTicks() {
		return miningTicks;
	}
}