package com.example.delivery.interaction;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.navigation.FlightNavigator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a 4x5 Obsidian Nether Portal frame on the Nether roof (or any flat ground),
 * ignites its interior using {@link Items#FLINT_AND_STEEL} (or {@link Items#FIRE_CHARGE}),
 * and guides the player into the lit {@link Blocks#NETHER_PORTAL} to return to the Overworld.
 */
public final class NetherPortalBuilder {

	public enum BuildResult {
		IN_PROGRESS,
		PORTAL_LIT_READY,
		MISSING_OBSIDIAN,
		MISSING_IGNITER
	}

	public record PlacementStep(BlockPos targetPos, BlockPos neighborPos, Direction clickFace, boolean corner) {
	}

	private BlockPos anchorPlayerPos = null;
	private final List<PlacementStep> placementPlan = new ArrayList<>(14);
	private BlockPos bottomInnerLeftPos = null;
	private BlockPos bottomInnerRightPos = null;
	private BlockPos activePortalInteriorPos = null;
	private int actionCooldownTicks = 0;

	public void reset() {
		this.anchorPlayerPos = null;
		this.placementPlan.clear();
		this.bottomInnerLeftPos = null;
		this.bottomInnerRightPos = null;
		this.activePortalInteriorPos = null;
		this.actionCooldownTicks = 0;
	}

	/**
	 * Initializes the 14-block 4x5 Nether Portal placement plan relative to the player's current block position.
	 * Every block in the plan is placed against an already-existing solid block face (starting from the Bedrock floor).
	 */
	public void initializePlan(BlockPos playerFeetPos) {
		this.anchorPlayerPos = playerFeetPos.immutable();
		this.placementPlan.clear();

		int px = playerFeetPos.getX();
		int py = playerFeetPos.getY();
		int pz = playerFeetPos.getZ() - 1; // 1 block North of the player, well within 4.5-block reach

		BlockPos b0 = new BlockPos(px - 1, py, pz);
		BlockPos b1 = new BlockPos(px, py, pz);
		BlockPos b2 = new BlockPos(px + 1, py, pz);
		BlockPos b3 = new BlockPos(px + 2, py, pz);

		this.bottomInnerLeftPos = b1;
		this.bottomInnerRightPos = b2;
		this.activePortalInteriorPos = b1.above();

		// 1-4: Bottom row placed on top of the Bedrock roof (py - 1)
		this.placementPlan.add(new PlacementStep(b0, b0.below(), Direction.UP, true));
		this.placementPlan.add(new PlacementStep(b1, b1.below(), Direction.UP, false));
		this.placementPlan.add(new PlacementStep(b2, b2.below(), Direction.UP, false));
		this.placementPlan.add(new PlacementStep(b3, b3.below(), Direction.UP, true));

		// 5-8: Left pillar (x = px - 1, y = py + 1 .. py + 4)
		BlockPos l1 = new BlockPos(px - 1, py + 1, pz);
		BlockPos l2 = new BlockPos(px - 1, py + 2, pz);
		BlockPos l3 = new BlockPos(px - 1, py + 3, pz);
		BlockPos l4 = new BlockPos(px - 1, py + 4, pz);
		this.placementPlan.add(new PlacementStep(l1, b0, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(l2, l1, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(l3, l2, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(l4, l3, Direction.UP, true));

		// 9-12: Right pillar (x = px + 2, y = py + 1 .. py + 4)
		BlockPos r1 = new BlockPos(px + 2, py + 1, pz);
		BlockPos r2 = new BlockPos(px + 2, py + 2, pz);
		BlockPos r3 = new BlockPos(px + 2, py + 3, pz);
		BlockPos r4 = new BlockPos(px + 2, py + 4, pz);
		this.placementPlan.add(new PlacementStep(r1, b3, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(r2, r1, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(r3, r2, Direction.UP, false));
		this.placementPlan.add(new PlacementStep(r4, r3, Direction.UP, true));

		// 13-14: Top bar (y = py + 4, x = px .. px + 1) placed inward from the top corners
		BlockPos t1 = new BlockPos(px, py + 4, pz);
		BlockPos t2 = new BlockPos(px + 1, py + 4, pz);
		this.placementPlan.add(new PlacementStep(t1, l4, Direction.EAST, false));
		this.placementPlan.add(new PlacementStep(t2, r4, Direction.WEST, false));
	}

	public List<PlacementStep> getPlacementPlan() {
		return List.copyOf(this.placementPlan);
	}

	public BlockPos getActivePortalInteriorPos() {
		return activePortalInteriorPos;
	}

	/**
	 * Executes one client tick of building and lighting the Nether Portal.
	 */
	public BuildResult tickBuildAndLight(Minecraft mc, DeliveryOrder currentOrder) {
		if (mc == null || mc.level == null || mc.player == null || mc.gameMode == null) {
			return BuildResult.IN_PROGRESS;
		}
		LocalPlayer player = mc.player;

		// 1. Check if a lit Nether Portal already exists nearby
		BlockPos existingPortal = findNearbyLitPortalBlock(mc, 6);
		if (existingPortal != null) {
			this.activePortalInteriorPos = existingPortal;
			return BuildResult.PORTAL_LIT_READY;
		}

		// 2. Initialize frame plan if not yet initialized
		if (this.anchorPlayerPos == null || this.placementPlan.isEmpty()) {
			initializePlan(player.blockPosition());
		}

		// Keep the player standing still outside the portal plane while building
		keepPlayerPositionedForBuild(mc);

		if (this.actionCooldownTicks > 0) {
			this.actionCooldownTicks--;
		}

		// 3. Find the next missing Obsidian block in the 14-block frame
		PlacementStep nextMissing = findNextMissingObsidianStep(mc);
		if (nextMissing != null) {
			// If all 10 mandatory non-corner blocks are already placed and we ran out of Obsidian, proceed to light!
			if (isMandatoryTenFrameBlocksPlaced(mc) && !hasItemInInventory(player, Items.OBSIDIAN)) {
				nextMissing = null;
			}
		}

		if (nextMissing != null) {
			if (!ensureItemInMainHand(mc, currentOrder, Items.OBSIDIAN)) {
				return BuildResult.MISSING_OBSIDIAN;
			}

			Vec3 hitVec = computeClickVector(nextMissing.neighborPos(), nextMissing.clickFace());
			double angleRem = FlightNavigator.rotateTowards(player, hitVec, 55.0f, 45.0f);

			if (angleRem <= 18.0 && this.actionCooldownTicks == 0) {
				BlockHitResult hitResult = new BlockHitResult(
					hitVec,
					nextMissing.clickFace(),
					nextMissing.neighborPos(),
					false
				);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				player.swing(InteractionHand.MAIN_HAND);
				this.actionCooldownTicks = 2;
				DeliveryBotMod.LOGGER.info(
					"[NetherPortalBuilder] Placed Obsidian at ({}, {}, {}) against ({}, {}, {}) face {}",
					nextMissing.targetPos().getX(), nextMissing.targetPos().getY(), nextMissing.targetPos().getZ(),
					nextMissing.neighborPos().getX(), nextMissing.neighborPos().getY(), nextMissing.neighborPos().getZ(),
					nextMissing.clickFace()
				);
			}
			return BuildResult.IN_PROGRESS;
		}

		// 4. All frame blocks are placed -> Ignite the portal interior using Flint and Steel (or Fire Charge)!
		if (!ensureItemInMainHand(mc, currentOrder, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)) {
			return BuildResult.MISSING_IGNITER;
		}

		BlockPos igniteBase = (this.bottomInnerLeftPos != null) ? this.bottomInnerLeftPos : player.blockPosition().north();
		Vec3 igniteVec = computeClickVector(igniteBase, Direction.UP);
		double angleRem = FlightNavigator.rotateTowards(player, igniteVec, 55.0f, 45.0f);

		if (angleRem <= 16.0 && this.actionCooldownTicks == 0) {
			BlockHitResult hitResult = new BlockHitResult(igniteVec, Direction.UP, igniteBase, false);
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
			player.swing(InteractionHand.MAIN_HAND);
			this.actionCooldownTicks = 6;
			DeliveryBotMod.LOGGER.info(
				"[NetherPortalBuilder] Ignited Nether Portal on top of ({}, {}, {})",
				igniteBase.getX(), igniteBase.getY(), igniteBase.getZ()
			);
		}

		BlockPos litPortal = findNearbyLitPortalBlock(mc, 6);
		if (litPortal != null) {
			this.activePortalInteriorPos = litPortal;
			return BuildResult.PORTAL_LIT_READY;
		}

		return BuildResult.IN_PROGRESS;
	}

	/**
	 * Guides the player into the lit {@link Blocks#NETHER_PORTAL} block and keeps them standing still
	 * inside it with all GUI screens closed until teleported to the Overworld.
	 *
	 * @return true if the player's body is currently inside a {@link Blocks#NETHER_PORTAL} block.
	 */
	public boolean enterPortalTick(Minecraft mc) {
		if (mc == null || mc.level == null || mc.player == null || mc.options == null) {
			return false;
		}
		LocalPlayer player = mc.player;

		// Ensure no GUI screen is open so Minecraft's portal teleportation timer advances
		if (mc.gui != null && mc.gui.screen() != null) {
			player.closeContainer();
		}

		if (player.getAbilities().flying) {
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}

		if ( isPlayerInsidePortal(mc)) {
			FlightNavigator.releaseAllMovementKeys(mc);
			Vec3 vel = player.getDeltaMovement();
			player.setDeltaMovement(0.0, vel.y, 0.0);
			return true;
		}

		BlockPos portalPos = findNearbyLitPortalBlock(mc, 8);
		if (portalPos != null) {
			this.activePortalInteriorPos = portalPos;
		}
		if (this.activePortalInteriorPos == null) {
			FlightNavigator.releaseAllMovementKeys(mc);
			return false;
		}

		Vec3 targetCenter = new Vec3(
			this.activePortalInteriorPos.getX() + 0.5,
			this.activePortalInteriorPos.getY() + 0.2,
			this.activePortalInteriorPos.getZ() + 0.5
		);
		FlightNavigator.rotateTowards(player, targetCenter, 35.0f, 25.0f);

		mc.options.keyUp.setDown(true);
		mc.options.keySprint.setDown(false);
		mc.options.keyShift.setDown(false);
		// Jump onto the bottom obsidian block (Y = 129) so the player steps into the portal frame
		boolean needsStepUp = player.getY() < this.activePortalInteriorPos.getY() - 0.1 || player.horizontalCollision;
		mc.options.keyJump.setDown(needsStepUp && player.onGround());
		return false;
	}

	/**
	 * Checks whether the player's bounding box or block position is currently overlapping a {@link Blocks#NETHER_PORTAL}.
	 */
	public static boolean isPlayerInsidePortal(Minecraft mc) {
		if (mc == null || mc.level == null || mc.player == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		BlockPos feet = player.blockPosition();
		if (mc.level.getBlockState(feet).is(Blocks.NETHER_PORTAL)
			|| mc.level.getBlockState(feet.above()).is(Blocks.NETHER_PORTAL)) {
			return true;
		}

		AABB box = player.getBoundingBox().deflate(0.05);
		int minX = (int) Math.floor(box.minX);
		int maxX = (int) Math.floor(box.maxX);
		int minY = (int) Math.floor(box.minY);
		int maxY = (int) Math.floor(box.maxY);
		int minZ = (int) Math.floor(box.minZ);
		int maxZ = (int) Math.floor(box.maxZ);

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (mc.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.NETHER_PORTAL)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Searches around the player for an active {@link Blocks#NETHER_PORTAL} block (preferring the lowest interior block).
	 */
	public static BlockPos findNearbyLitPortalBlock(Minecraft mc, int radius) {
		if (mc == null || mc.level == null || mc.player == null) {
			return null;
		}
		BlockPos center = mc.player.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;

		for (int dy = -2; dy <= 4; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos pos = center.offset(dx, dy, dz);
					BlockState state = mc.level.getBlockState(pos);
					if (state.is(Blocks.NETHER_PORTAL)) {
						// Prefer the bottom-most portal block so the player steps onto the frame base
						boolean isBottomPortalBlock = !mc.level.getBlockState(pos.below()).is(Blocks.NETHER_PORTAL);
						double distSq = center.distSqr(pos) + (isBottomPortalBlock ? 0.0 : 50.0);
						if (distSq < bestScore) {
							bestScore = distSq;
							best = pos.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	public int countPlacedObsidianInPlan(Minecraft mc) {
		if (mc == null || mc.level == null) {
			return 0;
		}
		int count = 0;
		for (PlacementStep step : this.placementPlan) {
			if (mc.level.getBlockState(step.targetPos()).is(Blocks.OBSIDIAN)) {
				count++;
			}
		}
		return count;
	}

	private PlacementStep findNextMissingObsidianStep(Minecraft mc) {
		for (PlacementStep step : this.placementPlan) {
			BlockState current = mc.level.getBlockState(step.targetPos());
			if (!current.is(Blocks.OBSIDIAN)) {
				// Ensure the neighbor block we click against is already present
				BlockState neighbor = mc.level.getBlockState(step.neighborPos());
				if (!neighbor.isAir()) {
					return step;
				}
			}
		}
		return null;
	}

	private boolean isMandatoryTenFrameBlocksPlaced(Minecraft mc) {
		for (PlacementStep step : this.placementPlan) {
			if (!step.corner() && !mc.level.getBlockState(step.targetPos()).is(Blocks.OBSIDIAN)) {
				return false;
			}
		}
		return !this.placementPlan.isEmpty();
	}

	private void keepPlayerPositionedForBuild(Minecraft mc) {
		if (this.anchorPlayerPos == null || mc.player == null) {
			return;
		}
		LocalPlayer player = mc.player;
		double targetX = this.anchorPlayerPos.getX() + 0.5;
		double targetZ = this.anchorPlayerPos.getZ() + 0.65;
		double dist = Math.hypot(player.getX() - targetX, player.getZ() - targetZ);
		if (dist > 0.35 && player.onGround()) {
			player.setPos(targetX, player.getY(), targetZ);
		}
		FlightNavigator.releaseAllMovementKeys(mc);
	}

	private static Vec3 computeClickVector(BlockPos neighborPos, Direction face) {
		return new Vec3(
			neighborPos.getX() + 0.5 + face.getStepX() * 0.5,
			neighborPos.getY() + 0.5 + face.getStepY() * 0.5,
			neighborPos.getZ() + 0.5 + face.getStepZ() * 0.5
		);
	}

	private static boolean hasItemInInventory(LocalPlayer player, Item... items) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				for (Item target : items) {
					if (stack.is(target)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean ensureItemInMainHand(Minecraft mc, DeliveryOrder currentOrder, Item... acceptedItems) {
		LocalPlayer player = mc.player;
		Inventory inv = player.getInventory();

		// 1. Check hotbar (slots 0..8)
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (matchesAny(stack, acceptedItems)) {
				if (inv.getSelectedSlot() != i) {
					inv.setSelectedSlot(i);
				}
				return true;
			}
		}

		// 2. Check main inventory (slots 9..35) and swap into a safe hotbar slot
		int packageSlot = GuiInventoryHelper.findDeliveryPackageSlot(mc, currentOrder);
		for (int i = 9; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (matchesAny(stack, acceptedItems)) {
				int targetHotbar = chooseSafeHotbarSlot(inv, packageSlot);
				if (mc.gameMode != null) {
					mc.gameMode.handleContainerInput(
						player.inventoryMenu.containerId,
						i,
						targetHotbar,
						ContainerInput.SWAP,
						player
					);
					inv.setSelectedSlot(targetHotbar);
					return true;
				}
			}
		}

		return false;
	}

	private static boolean matchesAny(ItemStack stack, Item... items) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		for (Item item : items) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	private static int chooseSafeHotbarSlot(Inventory inv, int packageSlot) {
		for (int i = 0; i < 9; i++) {
			if (i != packageSlot && inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		for (int i = 8; i >= 0; i--) {
			ItemStack s = inv.getItem(i);
			if (i != packageSlot
				&& !s.is(Items.OBSIDIAN)
				&& !s.is(Items.FLINT_AND_STEEL)
				&& !s.is(Items.FIRE_CHARGE)) {
				return i;
			}
		}
		return 7;
	}
}