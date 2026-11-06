package com.example.delivery.gui;

import com.example.delivery.automation.AutomationState;
import com.example.delivery.automation.DeliveryAutomationController;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.config.DeliveryConfig.NavigationMode;
import com.example.delivery.model.CustomerTarget;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.navigation.BaritoneNavigator;
import com.example.delivery.navigation.DeliveryLocationStorage;
import com.example.delivery.navigation.DeliveryLocationStorage.HomeHubCoordinate;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

/**
 * Renders a modern, movable glassmorphic HUD telemetry card that can be freely dragged
 * anywhere on the screen whenever the cursor is unlocked (e.g. Chat {@code T}, Inventory {@code E}).
 *
 * <p>Displays &amp; Interactive Controls:</p>
 * <ul>
 *   <li>Live Automation State, 8-step progress bar, and Navigation Mode ({@code Nether} vs {@code Multi-Home})</li>
 *   <li><b>Earnings Statistics:</b> Total Money Earned ({@code xu}), Total Run Time ({@code HH:mm:ss}), and Average {@code xu/min}</li>
 *   <li>Active Job, Difficulty Badge, Reward, and Baritone status</li>
 *   <li><b>4 Nether Homes ({@code H1..H4}):</b> Displays {@code H1:(-416,-829)}, {@code H2:(954,-123)}, {@code H3:(-938,263)}, {@code H4:(475,920)}.
 *       Clicking {@code H1}, {@code H2}, {@code H3}, or {@code H4} in the HUD automatically executes:
 *       {@code /home nether} &rarr; Baritone navigate to coordinates &rarr; {@code /sethome h1..h4}.</li>
 * </ul>
 */
public final class DeliveryHudOverlay {
	public static final int HUD_WIDTH = 248;

	private static boolean draggingHud = false;
	private static boolean wasLeftMouseDown = false;
	private static int dragOffsetX = 0;
	private static int dragOffsetY = 0;

	private DeliveryHudOverlay() {
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		DeliveryConfig cfg = DeliveryConfig.get();
		if (!cfg.showHudOverlay) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		// Hide HUD overlay behind the full-screen Control Dashboard to avoid overlap
		if (mc.gui != null && mc.gui.screen() instanceof DeliveryControlScreen) {
			return;
		}

		DeliveryAutomationController ctrl = DeliveryAutomationController.getInstance();
		AutomationState state = ctrl.getState();
		DeliveryOrder order = ctrl.getCurrentOrder();
		CustomerTarget target = ctrl.getCustomerTarget();
		double dist = ctrl.getCurrentDistanceToCustomer(mc);
		String errStr = ctrl.getLastErrorMessage();
		boolean hasError = errStr != null && !errStr.equals("None") && !errStr.isBlank();

		Font font = mc.font;
		int w = HUD_WIDTH;
		int h = hasError ? 238 : 224;

		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();

		int curX = Math.clamp(cfg.hudX, 2, Math.max(2, guiW - w - 2));
		int curY = Math.clamp(cfg.hudY, 2, Math.max(2, guiH - h - 2));

		boolean cursorUnlocked = !mc.mouseHandler.isMouseGrabbed();
		double mouseX = cursorUnlocked
			? mc.mouseHandler.xpos() * (double) guiW / (double) Math.max(1, mc.getWindow().getScreenWidth())
			: -1000.0;
		double mouseY = cursorUnlocked
			? mc.mouseHandler.ypos() * (double) guiH / (double) Math.max(1, mc.getWindow().getScreenHeight())
			: -1000.0;

		// Precompute interactive bounds for the 4 Nether Home buttons (H1, H2, H3, H4) and Calc ON/OFF pill
		int homesCardY = curY + 157;
		int homeBtnW = (w - 22) / 2;
		int homeBtnH = 11;
		int col1X = curX + 9;
		int col2X = curX + 9 + homeBtnW + 4;
		int row1Y = homesCardY + 14;
		int row2Y = homesCardY + 26;

		int calcBadgeW = 52;
		int calcBadgeX = curX + w - calcBadgeW - 9;
		int calcBadgeY = homesCardY + 3;
		int calcBadgeH = 10;

		handleMouseInputAndDrag(
			mc, cfg, ctrl, guiW, guiH, w, h,
			mouseX, mouseY,
			col1X, col2X, row1Y, row2Y, homeBtnW, homeBtnH,
			calcBadgeX, calcBadgeY, calcBadgeW, calcBadgeH
		);

		int x = Math.clamp(cfg.hudX, 2, Math.max(2, guiW - w - 2));
		int y = Math.clamp(cfg.hudY, 2, Math.max(2, guiH - h - 2));

		int accentColor = switch (state) {
			case IDLE -> 0xFF64748B;
			case PAUSED -> 0xFFF59E0B;
			case ERROR -> 0xFFEF4444;
			case DELIVERY_COMPLETED -> 0xFF10B981;
			default -> 0xFF38BDF8;
		};

		// Outer shadow & glassmorphic card background
		graphics.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
		graphics.fillGradient(x, y, x + w, y + h, 0xEC0F172A, 0xEC090D16);
		graphics.outline(x, y, w, h, draggingHud ? 0xFF38BDF8 : 0xFF1E293B);
		graphics.fill(x, y, x + w, y + 2, accentColor);

		// 1. Header Bar (Draggable Handle)
		int headerY = y + 7;
		String dragHint = cursorUnlocked ? " §8[Drag / Click H1-H4]" : "";
		graphics.text(font, "§b§l⚡ DELIVERY BOT" + dragHint, x + 8, headerY, 0xFFFFFFFF, true);

		String badgeText = switch (state) {
			case IDLE -> "STANDBY";
			case PAUSED -> "PAUSED";
			case ERROR -> "ERROR";
			default -> ((System.currentTimeMillis() / 500) % 2 == 0) ? "● ACTIVE" : "○ ACTIVE";
		};
		int badgeW = font.width(badgeText) + 8;
		int badgeX = x + w - badgeW - 7;
		graphics.fill(badgeX, headerY - 2, badgeX + badgeW, headerY + 10, (accentColor & 0x00FFFFFF) | 0x33000000);
		graphics.outline(badgeX, headerY - 2, badgeW, 12, accentColor);
		graphics.text(font, badgeText, badgeX + 4, headerY, accentColor, false);

		graphics.horizontalLine(x + 6, x + w - 7, y + 20, 0xFF1E293B);

		// 2. Workflow Progress & Mode Section
		int sec1Y = y + 24;
		String modeBadge = (cfg.navigationMode == NavigationMode.MULTI_HOME_MODE)
			? "§d[Multi-Home]"
			: "§6[Nether]";
		String stepPrefix = state.stepIndex() > 0
			? "§bStep " + state.stepIndex() + "/" + AutomationState.TOTAL_STEPS + " §8• §f"
			: "§7Status §8• §f";
		graphics.text(font, stepPrefix + trimText(font, state.displayName(), w - 95), x + 8, sec1Y, 0xFFFFFFFF, true);
		graphics.text(font, modeBadge, x + w - font.width(modeBadge) - 8, sec1Y, 0xFFFFFFFF, false);

		int barX = x + 8;
		int barY = sec1Y + 11;
		int barW = w - 16;
		int barH = 5;
		graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF1E293B);
		int fillW = Math.round(barW * state.progressRatio());
		if (fillW > 0) {
			graphics.fillGradient(barX, barY, barX + fillW, barY + barH, accentColor, 0xFF10B981);
		}
		graphics.outline(barX, barY, barW, barH, 0xFF334155);

		graphics.text(font, "§7" + trimText(font, ctrl.getStatusDetail(), w - 18), x + 8, barY + 8, 0xFFCBD5E1, false);

		// 3. Earnings & Runtime Statistics Sub-Card
		int earnY = barY + 19;
		int earnH = 38;
		graphics.fill(x + 6, earnY, x + w - 6, earnY + earnH, 0x99132238);
		graphics.outline(x + 6, earnY, w - 12, earnH, 0xFF1E3A5F);

		String totalEarnedStr = String.format("§a§l%,d xu", ctrl.getTotalEarnedXu());
		String doneStr = "§7Done: §f" + ctrl.getCompletedDeliveries();
		graphics.text(font, "§7Earned: " + totalEarnedStr, x + 11, earnY + 5, 0xFFFFFFFF, true);
		graphics.text(font, doneStr, x + w - font.width(doneStr) - 11, earnY + 5, 0xFFFFFFFF, false);

		String runTimeStr = "§b" + ctrl.getFormattedRunTime();
		String avgMinStr = String.format("§e%,d xu/m", ctrl.getAverageXuPerMinute());
		graphics.text(font, "§7Run Time: " + runTimeStr, x + 11, earnY + 17, 0xFFFFFFFF, false);
		graphics.text(font, "§7Avg: " + avgMinStr, x + w - font.width("§7Avg: " + avgMinStr) - 11, earnY + 17, 0xFFFFFFFF, false);

		String baritoneLine = BaritoneNavigator.getBaritoneStatusSummary();
		graphics.text(font, "§7Baritone: §3" + trimText(font, baritoneLine, w - 65), x + 11, earnY + 27, 0xFFFFFFFF, false);

		// 4. Active Job & Customer Sub-Card
		int jobY = earnY + earnH + 4;
		int jobH = 42;
		graphics.fill(x + 6, jobY, x + w - 6, jobY + jobH, 0x88162032);
		graphics.outline(x + 6, jobY, w - 12, jobH, 0xFF1E293B);

		String jobTitle = order != null
			? "§e§l" + order.orderId() + " " + formatDifficultyBadge(order.difficulty())
			: "§8No Active Job";
		String rewardText = order != null ? "§a" + order.formattedRewardSummary() : "§8-";
		graphics.text(font, "§7Job: " + jobTitle, x + 11, jobY + 5, 0xFFFFFFFF, true);
		graphics.text(font, rewardText, x + w - font.width(rewardText) - 11, jobY + 5, 0xFFFFFFFF, false);

		String custName = target != null ? "§a" + target.customerName() : "§8None";
		String distBadge = dist >= 0.0 ? String.format("§e%.1fm", dist) : "§8--";
		graphics.text(font, "§7Customer: " + custName, x + 11, jobY + 17, 0xFFFFFFFF, false);
		graphics.text(font, distBadge, x + w - font.width(distBadge) - 11, jobY + 17, 0xFFFFFFFF, false);

		String coordSummary = target != null
			? String.format("§fOW(%d, %d) §8| §dNeth(%d, %d)", target.x(), target.z(), target.netherX(), target.netherZ())
			: "§8Waiting for customer coordinates...";
		graphics.text(font, trimText(font, coordSummary, w - 22), x + 11, jobY + 29, 0xFFFFFFFF, false);

		// 5. 4 Calculated Nether Homes Card (Clickable H1, H2, H3, H4 + Calc ON/OFF Toggle)
		int homesY = jobY + jobH + 4;
		int homesH = 40;
		graphics.fill(x + 6, homesY, x + w - 6, homesY + homesH, 0x88162032);
		graphics.outline(x + 6, homesY, w - 12, homesH, 0xFF1E293B);

		DeliveryLocationStorage storage = DeliveryLocationStorage.getInstance();
		List<HomeHubCoordinate> homes = storage.getCalculatedHomes();
		int activeHome = ctrl.getSelectedHomeIndex();

		graphics.text(
			font,
			"§d§l4 NETHER HOMES §8(Saved: " + storage.getRecordedCount() + " pts)",
			x + 11,
			homesY + 4,
			0xFFFFFFFF,
			false
		);

		// Render Calc ON/OFF clickable badge in header
		int cBadgeX = x + w - calcBadgeW - 9;
		int cBadgeY = homesY + 3;
		boolean hoverCalc = cursorUnlocked && isInside(mouseX, mouseY, cBadgeX, cBadgeY, calcBadgeW, calcBadgeH);
		int calcBorder = cfg.coordinateCalculationEnabled ? 0xFF10B981 : 0xFFF59E0B;
		graphics.fill(cBadgeX, cBadgeY, cBadgeX + calcBadgeW, cBadgeY + calcBadgeH, hoverCalc ? 0x5538BDF8 : 0x440F172A);
		graphics.outline(cBadgeX, cBadgeY, calcBadgeW, calcBadgeH, hoverCalc ? 0xFF38BDF8 : calcBorder);
		String calcText = cfg.coordinateCalculationEnabled ? "§aCalc: ON" : "§eCalc: OFF";
		graphics.centeredText(font, calcText, cBadgeX + calcBadgeW / 2, cBadgeY + 1, 0xFFFFFFFF);

		if (homes.size() >= 4) {
			int bCol1X = x + 9;
			int bCol2X = x + 9 + homeBtnW + 4;
			int bRow1Y = homesY + 14;
			int bRow2Y = homesY + 26;

			renderHomeButton(graphics, font, homes.get(0), bCol1X, bRow1Y, homeBtnW, homeBtnH, activeHome == 1, cursorUnlocked && isInside(mouseX, mouseY, bCol1X, bRow1Y, homeBtnW, homeBtnH));
			renderHomeButton(graphics, font, homes.get(1), bCol2X, bRow1Y, homeBtnW, homeBtnH, activeHome == 2, cursorUnlocked && isInside(mouseX, mouseY, bCol2X, bRow1Y, homeBtnW, homeBtnH));
			renderHomeButton(graphics, font, homes.get(2), bCol1X, bRow2Y, homeBtnW, homeBtnH, activeHome == 3, cursorUnlocked && isInside(mouseX, mouseY, bCol1X, bRow2Y, homeBtnW, homeBtnH));
			renderHomeButton(graphics, font, homes.get(3), bCol2X, bRow2Y, homeBtnW, homeBtnH, activeHome == 4, cursorUnlocked && isInside(mouseX, mouseY, bCol2X, bRow2Y, homeBtnW, homeBtnH));
		}

		// Optional Error Banner
		int footerY = homesY + homesH + 5;
		if (hasError) {
			graphics.fill(x + 6, footerY - 1, x + w - 6, footerY + 11, 0x55EF4444);
			graphics.text(font, "§c⚠ " + trimText(font, errStr, w - 28), x + 10, footerY + 1, 0xFFFF6B6B, false);
			footerY += 14;
		}

		// 6. Footer Keybind Hints
		graphics.centeredText(font, "§8[§bR§8] Start/Stop   [§eP§8] Pause   [§dO§8] Dashboard", x + w / 2, footerY, 0xFF94A3B8);
	}

	private static void renderHomeButton(
		GuiGraphicsExtractor graphics,
		Font font,
		HomeHubCoordinate home,
		int bx,
		int by,
		int bw,
		int bh,
		boolean isActive,
		boolean isHovered
	) {
		if (isHovered) {
			graphics.fill(bx, by, bx + bw, by + bh, 0x5538BDF8);
			graphics.outline(bx, by, bw, bh, 0xFF38BDF8);
		} else if (isActive) {
			graphics.fill(bx, by, bx + bw, by + bh, 0x3310B981);
			graphics.outline(bx, by, bw, bh, 0xFF10B981);
		}
		String prefix = isHovered
			? "§b§lH" + home.homeIndex() + ":"
			: (isActive ? "§a§lH" + home.homeIndex() + ":" : "§7H" + home.homeIndex() + ":");
		String label = prefix + String.format("§f(%d,%d)", home.netherX(), home.netherZ());
		graphics.text(font, label, bx + 2, by + 2, 0xFFFFFFFF, false);
	}

	private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private static void handleMouseInputAndDrag(
		Minecraft mc,
		DeliveryConfig cfg,
		DeliveryAutomationController ctrl,
		int guiW,
		int guiH,
		int hudW,
		int hudH,
		double mouseX,
		double mouseY,
		int col1X,
		int col2X,
		int row1Y,
		int row2Y,
		int homeBtnW,
		int homeBtnH,
		int calcBadgeX,
		int calcBadgeY,
		int calcBadgeW,
		int calcBadgeH
	) {
		if (mc.mouseHandler.isMouseGrabbed()) {
			wasLeftMouseDown = false;
			if (draggingHud) {
				draggingHud = false;
				cfg.save();
			}
			return;
		}

		long windowHandle = mc.getWindow().handle();
		boolean leftDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean clickedEdge = leftDown && !wasLeftMouseDown;
		wasLeftMouseDown = leftDown;

		int curX = Math.clamp(cfg.hudX, 2, Math.max(2, guiW - hudW - 2));
		int curY = Math.clamp(cfg.hudY, 2, Math.max(2, guiH - hudH - 2));

		if (clickedEdge && !draggingHud) {
			// Check if the user clicked H1, H2, H3, or H4 in the HUD
			if (isInside(mouseX, mouseY, col1X, row1Y, homeBtnW, homeBtnH)) {
				ctrl.startAutoSetHome(mc, 1);
				return;
			}
			if (isInside(mouseX, mouseY, col2X, row1Y, homeBtnW, homeBtnH)) {
				ctrl.startAutoSetHome(mc, 2);
				return;
			}
			if (isInside(mouseX, mouseY, col1X, row2Y, homeBtnW, homeBtnH)) {
				ctrl.startAutoSetHome(mc, 3);
				return;
			}
			if (isInside(mouseX, mouseY, col2X, row2Y, homeBtnW, homeBtnH)) {
				ctrl.startAutoSetHome(mc, 4);
				return;
			}
			// Check if the user clicked the Calc: ON/OFF toggle pill in the HUD
			if (isInside(mouseX, mouseY, calcBadgeX, calcBadgeY, calcBadgeW, calcBadgeH)) {
				cfg.coordinateCalculationEnabled = !cfg.coordinateCalculationEnabled;
				cfg.save();
				return;
			}
		}

		if (leftDown) {
			if (!draggingHud) {
				boolean insideHud = mouseX >= curX && mouseX <= curX + hudW && mouseY >= curY && mouseY <= curY + hudH;
				if (insideHud) {
					draggingHud = true;
					dragOffsetX = (int) Math.round(mouseX) - curX;
					dragOffsetY = (int) Math.round(mouseY) - curY;
				}
			} else {
				cfg.hudX = Math.clamp((int) Math.round(mouseX) - dragOffsetX, 2, Math.max(2, guiW - hudW - 2));
				cfg.hudY = Math.clamp((int) Math.round(mouseY) - dragOffsetY, 2, Math.max(2, guiH - hudH - 2));
			}
		} else if (draggingHud) {
			draggingHud = false;
			cfg.save();
		}
	}

	public static String formatDifficultyBadge(String difficulty) {
		if (difficulty == null || difficulty.isBlank()) {
			return "";
		}
		String lower = difficulty.toLowerCase();
		if (lower.contains("siêu khó") || lower.contains("cực khó") || lower.contains("huyền thoại")) {
			return "§d§l[" + difficulty + "]";
		}
		if (lower.contains("rất khó") || lower.contains("khó")) {
			return "§c[" + difficulty + "]";
		}
		if (lower.contains("thường") || lower.contains("trung bình")) {
			return "§b[" + difficulty + "]";
		}
		return "§a[" + difficulty + "]";
	}

	private static String trimText(Font font, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (font.width(sb.toString() + c + "...") > maxWidth) {
				break;
			}
			sb.append(c);
		}
		return sb.append("...").toString();
	}
}
