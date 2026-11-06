package com.example.delivery;

import static org.junit.jupiter.api.Assertions.*;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.example.delivery.chat.ChatMonitor;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.interaction.GuiInventoryHelper;
import com.example.delivery.interaction.NetherPortalBuilder;
import com.example.delivery.interaction.NpcDetector;
import com.example.delivery.model.CustomerTarget;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.navigation.DeliveryLocationStorage;
import com.example.delivery.navigation.DeliveryLocationStorage.HomeHubCoordinate;
import com.example.delivery.navigation.RtpController;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit verification tests for the Automated Delivery Bot using exact strings, tooltips,
 * coordinates, and server messages captured from the two gameplay videos.
 */
class DeliveryVerificationTest {

	@BeforeEach
	void setUp() {
		ChatMonitor.getInstance().resetOrderState();
	}

	@Test
	void testParseOrderFromGameplayVideoTooltip() {
		List<String> videoTooltip = List.of(
			"§6§lĐƠN #5 §c[Khó]",
			"§7Hàng: §f1 x §aBánh bí ngô Ngon ★★",
			"§7Tiền vốn lấy hàng: §e437,500 xu",
			"§7Lương: §e2,000,000 xu + 1,900,000 xu §7mỗi 1000 block khoảng cách",
			"§7Thưởng cấp 2: §a+5% lương §8(đã tính khi nhận lương)",
			"§7Khách cách bạn: §f5,000 - 10,000 block",
			"§7Thưởng thêm: §d1 Capsule",
			"§a§lBẤM TRÁI: NHẬN ĐƠN"
		);

		DeliveryOrder order = GuiInventoryHelper.parseOrderFromLines(17, videoTooltip);
		assertNotNull(order, "Order #5 from video should be parsed as valid");
		assertEquals("#5", order.orderId());
		assertEquals("Khó", order.difficulty());
		assertEquals(1, order.quantity());
		assertEquals("Bánh bí ngô Ngon ★★", order.itemName());
		assertEquals(437500L, order.capitalCostXu());
		assertEquals("5,000 - 10,000 block", order.distanceRangeInfo());
		assertEquals(17, order.slotIndex());
		assertFalse(order.alreadyAccepted());
	}

	@Test
	void testParseAlreadyAcceptedOrderAndRejectCancelItem() {
		List<String> acceptedTooltip = List.of(
			"§6§lĐƠN #5 §c[Khó]",
			"§7Hàng: §f1 x §aBánh bí ngô Ngon ★★",
			"§7Tiền vốn lấy hàng: §e437,500 xu",
			"§7Khách cách bạn: §f5,000 - 10,000 block",
			"§e§lĐÃ NHẬN §7- đến §eCỬA HÀNG §7lấy hàng",
			"§7Bấm phải hoặc gõ /giaohang huy: hủy đơn (chưa mất gì)"
		);

		DeliveryOrder order = GuiInventoryHelper.parseOrderFromLines(17, acceptedTooltip);
		assertNotNull(order);
		assertTrue(order.alreadyAccepted());

		List<String> cancelButtonTooltip = List.of(
			"§c§lHủy đơn giao hàng",
			"§7Bấm để đóng hoặc hủy đơn"
		);
		assertNull(GuiInventoryHelper.parseOrderFromLines(26, cancelButtonTooltip));
	}

	@Test
	void testBakeryShipperCollectButtonAndPackageMatching() {
		List<String> shipperBtnLines = List.of(
			"§a§l🚚 LẤY HÀNG (Shipper)",
			"§7Nhận đồ ăn của đơn bạn đã nhận trong điện thoại /giaohang"
		);
		assertTrue(GuiInventoryHelper.isBakeryShipperCollectButton(shipperBtnLines));

		DeliveryOrder order = new DeliveryOrder(
			"#5", "Khó", "Bánh bí ngô Ngon ★★", 1, 437500L, "5,000 - 10,000 block", 17, false, List.of()
		);
		List<String> receivedBundleLines = List.of(
			"§6Gói hàng §7[§aBánh bí ngô Ngon ★★§7]"
		);
		assertTrue(GuiInventoryHelper.matchesDeliveryPackage(receivedBundleLines, order));

		List<String> swordLines = List.of("§bNetherite Sword");
		assertFalse(GuiInventoryHelper.matchesDeliveryPackage(swordLines, order));
	}

	@Test
	void testParseCustomerCoordinatesFromChatAndRtpDistanceMath() {
		String chatLine = "§7[§b🧭§7] §eKhách §fChị Tư §eđang chờ ở §bX -7272 Y 153 Z 1944 §7(world). §fCầm gói hàng bấm vào khách. La bàn đã chỉ về đó.";
		ChatMonitor.getInstance().onChatMessageText(chatLine);

		CustomerTarget target = ChatMonitor.getInstance().getLatestCustomerTarget();
		assertNotNull(target, "Customer coordinates must be dynamically extracted from chat");
		assertEquals("Chị Tư", target.customerName());
		assertEquals(-7272, target.x());
		assertEquals(153, target.y());
		assertEquals(1944, target.z());
		assertEquals("world", target.worldName());

		// Video 1 @ 22.0s: RTP lands at x=-5731 y=85 z=7595 -> server actionbar shows 5,857m (> 5,000 blocks)
		double distAfterFirstRtp = RtpController.calculateHorizontalDistance(-5731, 7595, target.x(), target.z());
		assertEquals(5857.34, distAfterFirstRtp, 0.5);
		assertTrue(distAfterFirstRtp > 5000.0, "5,857m is > 5,000m, so another /rtp is required");

		// Suppose next RTP lands at x=-4500, z=3000 -> distance ~ 2,966m <= 5,000m
		double distAfterSecondRtp = RtpController.calculateHorizontalDistance(-4500, 3000, target.x(), target.z());
		assertTrue(distAfterSecondRtp <= 5000.0, "2,966m is <= 5,000m, so RTP stops and flight begins");
	}

	@Test
	void testNpcNameDetectionAndDistinction() {
		assertTrue(NpcDetector.isBakeryNpcName("§6🥐 TIỆM BÁNH"));
		assertFalse(NpcDetector.isBakeryNpcName("§eChị Tư §7(Khách hàng)"));

		assertTrue(NpcDetector.isCustomerNameMatch("§eChị Tư §7(Khách hàng)", "Chị Tư"));
		assertFalse(NpcDetector.isCustomerNameMatch("§6🥐 TIỆM BÁNH", "Chị Tư"));
		assertFalse(NpcDetector.isCustomerNameMatch("§eAnh Ba §7(Khách hàng)", "Chị Tư"));
	}

	@Test
	void testRtpAndDeliverySuccessChatSignals() {
		ChatMonitor monitor = ChatMonitor.getInstance();

		monitor.onChatMessageText("[RTP] Sẽ dịch chuyển sau 5 giây! Vui lòng không di chuyển!");
		assertTrue(monitor.isRtpWarmupActive());
		assertFalse(monitor.isRtpCompleted());

		monitor.onChatMessageText("[RTP] Bạn đã được dịch chuyển đến x=-5731 y=85 z=7595");
		assertFalse(monitor.isRtpWarmupActive());
		assertTrue(monitor.isRtpCompleted());

		monitor.onChatMessageText("[✔] Chị Tư: \"Cảm ơn bạn nhiều!\" Nhận lương: 17,283,945 xu + 1 Capsule");
		assertTrue(monitor.isDeliverySuccessConfirmed());

		// Resetting for next cycle must clear all flags and coordinates
		monitor.resetOrderState();
		assertNull(monitor.getLatestCustomerTarget());
		assertFalse(monitor.isDeliverySuccessConfirmed());
	}

	@Test
	void testNetherCoordinateCalculationAndHomeNetherChatFromVideo4() {
		ChatMonitor monitor = ChatMonitor.getInstance();

		// Video 4 @ 2.5s: Ông Chín waiting at X -3739 Y 96 Z 7382 (world)
		String video4Chat = "[🧭] Khách Ông Chín đang chờ ở X -3739 Y 96 Z 7382 (world). Cầm gói hàng bấm vào khách. La bàn đã chỉ về đó.";
		monitor.onChatMessageText(video4Chat);

		CustomerTarget target = monitor.getLatestCustomerTarget();
		assertNotNull(target);
		assertEquals("Ông Chín", target.customerName());
		assertEquals(-3739, target.x());
		assertEquals(96, target.y());
		assertEquals(7382, target.z());

		// Nether coordinates = Overworld X / 8, Z / 8
		assertEquals(-467, target.netherX());
		assertEquals(922, target.netherZ());
		assertEquals(0.0, target.horizontalDistanceInNetherTo(-467 + 0.5, 922 + 0.5), 1e-6);

		// Video 4 @ 4.7s: /home nether confirmation
		assertFalse(monitor.isHomeNetherConfirmed());
		monitor.onChatMessageText("Teleporting to nether.");
		assertTrue(monitor.isHomeNetherConfirmed());
	}

	@Test
	void testNetherPortalBuilderPlanConnectivityAndReachFromVideo5() {
		NetherPortalBuilder builder = new NetherPortalBuilder();
		BlockPos playerFeet = new BlockPos(-467, 128, 922);
		builder.initializePlan(playerFeet);

		List<NetherPortalBuilder.PlacementStep> plan = builder.getPlacementPlan();
		assertEquals(14, plan.size(), "Standard 4x5 Nether Portal frame should contain 14 Obsidian blocks");

		// Simulate bedrock roof at Y = 127
		Set<BlockPos> placedSolidBlocks = new HashSet<>();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				placedSolidBlocks.add(new BlockPos(playerFeet.getX() + dx, 127, playerFeet.getZ() + dz));
			}
		}

		double eyeX = playerFeet.getX() + 0.5;
		double eyeY = playerFeet.getY() + 1.62;
		double eyeZ = playerFeet.getZ() + 0.5;

		int nonCornerCount = 0;
		for (NetherPortalBuilder.PlacementStep step : plan) {
			assertTrue(
				placedSolidBlocks.contains(step.neighborPos()),
				"Neighbor block " + step.neighborPos() + " must already exist before placing " + step.targetPos()
			);
			assertEquals(
				step.targetPos(),
				step.neighborPos().relative(step.clickFace()),
				"Clicking neighbor on face must place block at targetPos"
			);

			double distToNeighborCenter = Math.sqrt(
				Math.pow((step.neighborPos().getX() + 0.5) - eyeX, 2)
					+ Math.pow((step.neighborPos().getY() + 0.5) - eyeY, 2)
					+ Math.pow((step.neighborPos().getZ() + 0.5) - eyeZ, 2)
			);
			assertTrue(distToNeighborCenter <= 4.5, "Every portal block placement must be within 4.5-block survival reach");

			placedSolidBlocks.add(step.targetPos());
			if (!step.corner()) {
				nonCornerCount++;
			}
		}

		assertEquals(10, nonCornerCount, "4x5 frame has 10 mandatory non-corner blocks and 4 corner blocks");
		assertEquals(new BlockPos(-467, 129, 921), builder.getActivePortalInteriorPos());
	}

	@Test
	void testPrioritizeHardestAndHighestRewardOrderFromVideo3() {
		// Video 3 @ 2.5s: ĐƠN #1 [Thường] (1,312,500 xu)
		DeliveryOrder normalOrder = GuiInventoryHelper.parseOrderFromLines(10, List.of(
			"§6§lĐƠN #1 §a[Thường]",
			"§7Hàng: §f1 x §aBánh quy Hoàn hảo ★★★",
			"§7Tiền vốn lấy hàng: §e225,000 xu",
			"§7Lương: §e1,312,500 xu + 1,900,000 xu §7mỗi 1000 block khoảng cách",
			"§7Khách cách bạn: §f5,000 - 10,000 block",
			"§a§lBẤM TRÁI: NHẬN ĐƠN"
		));

		// Video 3 @ 1.5s: ĐƠN #4 [Siêu khó] (6,300,000 xu + 3 Capsule) — note slot 16 is lower than slot 17 (#5)
		DeliveryOrder superHardOrder = GuiInventoryHelper.parseOrderFromLines(16, List.of(
			"§6§lĐƠN #4 §d[Siêu khó]",
			"§7Hàng: §f1 x §dBánh kem Tạm ★",
			"§7Tiền vốn lấy hàng: §e1,312,500 xu",
			"§7Lương: §e6,300,000 xu + 1,900,000 xu §7mỗi 1000 block khoảng cách",
			"§7Khách cách bạn: §f5,000 - 10,000 block",
			"§7Thưởng thêm: §d3 Capsule",
			"§a§lBẤM TRÁI: NHẬN ĐƠN"
		));

		// Video 1: ĐƠN #5 [Khó] (2,000,000 xu + 1 Capsule) in slot 17
		DeliveryOrder hardOrderSlot5 = GuiInventoryHelper.parseOrderFromLines(17, List.of(
			"§6§lĐƠN #5 §c[Khó]",
			"§7Hàng: §f1 x §aBánh bí ngô Ngon ★★",
			"§7Tiền vốn lấy hàng: §e437,500 xu",
			"§7Lương: §e2,000,000 xu + 1,900,000 xu §7mỗi 1000 block khoảng cách",
			"§7Khách cách bạn: §f5,000 - 10,000 block",
			"§7Thưởng thêm: §d1 Capsule",
			"§a§lBẤM TRÁI: NHẬN ĐƠN"
		));

		assertNotNull(normalOrder);
		assertNotNull(superHardOrder);
		assertNotNull(hardOrderSlot5);

		assertEquals(6300000L, superHardOrder.baseRewardXu());
		assertEquals(3, superHardOrder.bonusCapsules());
		assertTrue(superHardOrder.difficultyRank() > hardOrderSlot5.difficultyRank());
		assertTrue(hardOrderSlot5.difficultyRank() > normalOrder.difficultyRank());

		// Must pick ĐƠN #4 [Siêu khó] even though ĐƠN #5 is in a higher slot index!
		DeliveryOrder chosen = GuiInventoryHelper.pickOrderToProcess(
			List.of(normalOrder, superHardOrder, hardOrderSlot5),
			true
		);
		assertNotNull(chosen);
		assertEquals("#4", chosen.orderId());
		assertEquals("Siêu khó", chosen.difficulty());
	}

	@Test
	void testBaritoneGoalsForNetherAndOverworldNavigation() {
		CustomerTarget target = new CustomerTarget("Ông Chín", -3739, 96, 7382, "world");
		GoalXZ netherGoal = new GoalXZ(target.netherX(), target.netherZ());
		assertEquals(-467, netherGoal.getX());
		assertEquals(922, netherGoal.getZ());
		assertTrue(netherGoal.isInGoal(-467, 128, 922));

		GoalNear overworldGoal = new GoalNear(new BlockPos(target.x(), target.y(), target.z()), 2);
		assertTrue(overworldGoal.isInGoal(target.x() + 1, target.y(), target.z() - 1));

		GoalBlock portalGoal = new GoalBlock(-467, 129, 921);
		assertTrue(portalGoal.isInGoal(-467, 129, 921));
	}

	@Test
	void testHomeLambanhAndEarningsExtractionFromChat() {
		ChatMonitor monitor = ChatMonitor.getInstance();

		assertFalse(monitor.isWarpConfirmed());
		monitor.onChatMessageText("Teleporting to lambanh.");
		assertTrue(monitor.isWarpConfirmed(), "/home lambanh confirmation should set warpConfirmed");

		// Package pickup message with expected payout
		monitor.onChatMessageText(
			"[📦] Đã lấy hàng (vốn 281,250 xu). Khách cách khoảng 8,312 block, lương khi giao xong: 18,157,440 xu."
		);
		assertEquals(18157440L, monitor.resolveCompletedOrderEarnings(0L));

		// Completed delivery message with actual earned xu
		monitor.onChatMessageText("[✔] Ông Chín: \"Cảm ơn bạn nhiều!\" Nhận lương: 19,500,000 xu");
		assertTrue(monitor.isDeliverySuccessConfirmed());
		assertEquals(19500000L, monitor.resolveCompletedOrderEarnings(0L));
	}

	@Test
	void testDeliveryLocationStorageBaselineToggleAndHomeH1ToH4Selection() {
		DeliveryLocationStorage storage = DeliveryLocationStorage.getInstance();
		DeliveryConfig cfg = DeliveryConfig.get();
		cfg.coordinateCalculationEnabled = true;
		storage.clearAllRecordedLocations();

		// 1. Verify baseline parameters match the reference image:
		// H1: (-416, -829), H2: (954, -123), H3: (-938, 263), H4: (475, 920)
		List<HomeHubCoordinate> baselineHomes = storage.getCalculatedHomes();
		assertEquals(4, baselineHomes.size());
		assertEquals(-416, baselineHomes.get(0).netherX());
		assertEquals(-829, baselineHomes.get(0).netherZ());
		assertEquals("home h1", baselineHomes.get(0).homeCommand());
		assertEquals("sethome h1", baselineHomes.get(0).setHomeCommand());

		assertEquals(954, baselineHomes.get(1).netherX());
		assertEquals(-123, baselineHomes.get(1).netherZ());
		assertEquals("home h2", baselineHomes.get(1).homeCommand());
		assertEquals("sethome h2", baselineHomes.get(1).setHomeCommand());

		assertEquals(-938, baselineHomes.get(2).netherX());
		assertEquals(263, baselineHomes.get(2).netherZ());
		assertEquals("home h3", baselineHomes.get(2).homeCommand());
		assertEquals("sethome h3", baselineHomes.get(2).setHomeCommand());

		assertEquals(475, baselineHomes.get(3).netherX());
		assertEquals(920, baselineHomes.get(3).netherZ());
		assertEquals("home h4", baselineHomes.get(3).homeCommand());
		assertEquals("sethome h4", baselineHomes.get(3).setHomeCommand());

		// 2. When coordinateCalculationEnabled is OFF, recording new destinations must preserve the saved parameters
		cfg.coordinateCalculationEnabled = false;
		storage.recordDeliveryDestination(new CustomerTarget("Khách 1", -6400, 90, -6400, "world"));
		assertEquals(0, storage.getRecordedCount(), "Should not record or modify homes when calculation is OFF");
		List<HomeHubCoordinate> preservedBaseline = storage.getCalculatedHomes();
		assertEquals(-416, preservedBaseline.get(0).netherX());
		assertEquals(-829, preservedBaseline.get(0).netherZ());
		assertEquals(475, preservedBaseline.get(3).netherX());
		assertEquals(920, preservedBaseline.get(3).netherZ());

		// Nearest home selection using saved baseline parameters:
		// Ông Chín at Overworld (-3739, 96, 7382) -> Nether (-467, 922) is closest to H3 (-938, 263) or H4 (475, 920)
		// Let's check target at Overworld (3800, 95, 7360) -> Nether (475, 920) -> exact match for H4!
		CustomerTarget targetNearH4 = new CustomerTarget("Ông Chín", 3800, 95, 7360, "world");
		HomeHubCoordinate nearestFromSaved = storage.findNearestHome(targetNearH4);
		assertEquals(4, nearestFromSaved.homeIndex());
		assertEquals("home h4", nearestFromSaved.homeCommand());
		assertEquals("sethome h4", nearestFromSaved.setHomeCommand());

		// 3. When coordinateCalculationEnabled is ON, continuously calculate and update the 4 homes
		cfg.coordinateCalculationEnabled = true;
		storage.recordDeliveryDestination(new CustomerTarget("Khách 1", -6400, 90, -6400, "world")); // Nether (-800, -800)
		storage.recordDeliveryDestination(new CustomerTarget("Khách 2", -6320, 90, -6480, "world")); // Nether (-790, -810)
		storage.recordDeliveryDestination(new CustomerTarget("Khách 3", 6400, 90, -6400, "world"));  // Nether (800, -800)
		storage.recordDeliveryDestination(new CustomerTarget("Khách 4", -6400, 90, 6400, "world"));  // Nether (-800, 800)
		storage.recordDeliveryDestination(new CustomerTarget("Khách 5", 6400, 90, 6400, "world"));   // Nether (800, 800)

		assertEquals(5, storage.getRecordedCount());
		List<HomeHubCoordinate> updatedHomes = storage.getCalculatedHomes();
		assertEquals(4, updatedHomes.size());
		assertEquals(-795, updatedHomes.get(0).netherX());
		assertEquals(-805, updatedHomes.get(0).netherZ());
		assertEquals(800, updatedHomes.get(3).netherX());
		assertEquals(800, updatedHomes.get(3).netherZ());

		// Turning calculation OFF again must preserve the newly calculated (-795, -805) .. (800, 800) parameters!
		cfg.coordinateCalculationEnabled = false;
		storage.recordDeliveryDestination(new CustomerTarget("Khách 6", 0, 90, 0, "world"));
		assertEquals(5, storage.getRecordedCount(), "Calculation OFF must preserve the last calculated parameters");
		assertEquals(800, storage.getHomeByIndex(4).netherX());
		assertEquals(800, storage.getHomeByIndex(4).netherZ());

		// Verify ChatMonitor detects /home h1..h4 teleport confirmation
		ChatMonitor monitor = ChatMonitor.getInstance();
		monitor.clearHomeNetherConfirmed();
		assertFalse(monitor.isHomeNetherConfirmed());
		monitor.onChatMessageText("Teleporting to h2.");
		assertTrue(monitor.isHomeNetherConfirmed());

		// Reset back to baseline for clean state
		cfg.coordinateCalculationEnabled = true;
		storage.clearAllRecordedLocations();
	}
}
