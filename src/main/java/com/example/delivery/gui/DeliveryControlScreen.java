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
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Modern, polished Control Center &amp; Settings Dashboard for the Automated Delivery Bot:
 * <ul>
 *   <li>Top action bar: Start, Pause, Resume, Stop, and Cancel Baritone Navigation</li>
 *   <li>Left Column: Live Telemetry, Earnings Statistics (Total Earned, Run Time, Avg/Min),
 *       and the <b>4 Optimal Nether Home Coordinates Calculator</b> ({@code Home 1..4}) with 1-click Baritone travel buttons</li>
 *   <li>Right Column: Navigation Mode Toggle ({@code Nether Mode} vs {@code Multi-Home Mode}),
 *       Hardest Job Priority toggle, Kick Auto-Recovery toggle, Baritone settings, and HUD controls</li>
 * </ul>
 */
public final class DeliveryControlScreen extends Screen {
	private final Screen parent;

	public DeliveryControlScreen(Screen parent) {
		super(Component.literal("Automated Delivery Bot — Control Dashboard"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		DeliveryConfig cfg = DeliveryConfig.get();
		DeliveryAutomationController ctrl = DeliveryAutomationController.getInstance();
		Minecraft mc = Minecraft.getInstance();

		int centerX = this.width / 2;
		int panelW = Math.min(392, this.width - 16);
		int leftX = centerX - panelW / 2;

		// 1. Top Quick-Action Control Bar (5 buttons)
		int topBtnY = 30;
		int gap = 4;
		int btnW = (panelW - gap * 4) / 5;

		this.addRenderableWidget(Button.builder(Component.literal("§a▶ Start"), b -> {
			ctrl.startAutomation(mc);
			this.onClose();
		}).bounds(leftX, topBtnY, btnW, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("§e⏸ Pause"), b -> {
			ctrl.pauseAutomation(mc, "Paused from Dashboard");
		}).bounds(leftX + (btnW + gap), topBtnY, btnW, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("§b⏯ Resume"), b -> {
			ctrl.resumeAutomation(mc);
			this.onClose();
		}).bounds(leftX + (btnW + gap) * 2, topBtnY, btnW, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("§c⏹ Stop"), b -> {
			ctrl.stopAutomation(mc, "Stopped from Dashboard");
		}).bounds(leftX + (btnW + gap) * 3, topBtnY, btnW, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("§d🛑 Cancel Nav"), b -> {
			BaritoneNavigator.stopBaritone(mc);
		}).bounds(leftX + (btnW + gap) * 4, topBtnY, btnW, 18).build());

		int colW = (panelW - 8) / 2;
		int rightColX = leftX + colW + 8;

		// 2. Left Column Bottom: 1-Click Automated "[H1..H4]" buttons (/home nether -> Baritone -> /sethome h1..h4)
		List<HomeHubCoordinate> homes = DeliveryLocationStorage.getInstance().getCalculatedHomes();
		int goBtnY = this.height - 50;
		int goBtnW = (colW - 16 - 6) / 4;
		for (int i = 0; i < Math.min(4, homes.size()); i++) {
			HomeHubCoordinate home = homes.get(i);
			this.addRenderableWidget(Button.builder(Component.literal("§dH" + home.homeIndex()), b -> {
				ctrl.startAutoSetHome(mc, home.homeIndex());
			}).bounds(leftX + 8 + i * (goBtnW + 2), goBtnY, goBtnW, 16).build());
		}

		// 3. Right Column: Navigation Mode Toggle, Coordinate Calculation Toggle & Settings Controls
		int ctrlX = rightColX + 8;
		int ctrlW = colW - 16;
		int rowY = 70;
		int rowStep = 19;

		// Navigation Mode Toggle Switch (Nether Mode vs Multi-Home Mode /home h1-h4)
		this.addRenderableWidget(Button.builder(formatNavModeLabel(cfg.navigationMode), b -> {
			cfg.navigationMode = cfg.navigationMode.next();
			cfg.save();
			b.setMessage(formatNavModeLabel(cfg.navigationMode));
		}).bounds(ctrlX, rowY, ctrlW, 17).build());
		rowY += rowStep;

		// Coordinate Calculation ON/OFF Toggle
		addToggleButton(
			ctrlX, rowY, ctrlW,
			"Coord Calculation", cfg.coordinateCalculationEnabled,
			val -> cfg.coordinateCalculationEnabled = val
		);
		rowY += rowStep;

		addToggleButton(
			ctrlX, rowY, ctrlW,
			"Prioritize Hardest Job", cfg.preferHardestOrder,
			val -> cfg.preferHardestOrder = val
		);
		rowY += rowStep;

		addToggleButton(
			ctrlX, rowY, ctrlW,
			"Auto Kick Recovery", cfg.autoReconnectAndRecover,
			val -> cfg.autoReconnectAndRecover = val
		);
		rowY += rowStep;

		int halfCtrlW = (ctrlW - 4) / 2;
		addToggleButton(
			ctrlX, rowY, halfCtrlW,
			"Break", cfg.baritoneAllowBreak,
			val -> {
				cfg.baritoneAllowBreak = val;
				BaritoneNavigator.configureForDeliveryNavigation(cfg);
			}
		);
		addToggleButton(
			ctrlX + halfCtrlW + 4, rowY, halfCtrlW,
			"Place", cfg.baritoneAllowPlace,
			val -> {
				cfg.baritoneAllowPlace = val;
				BaritoneNavigator.configureForDeliveryNavigation(cfg);
			}
		);
		rowY += rowStep;

		addToggleButton(
			ctrlX, rowY, ctrlW,
			"Baritone Auto-Sprint", cfg.baritoneAllowSprint,
			val -> {
				cfg.baritoneAllowSprint = val;
				BaritoneNavigator.configureForDeliveryNavigation(cfg);
			}
		);
		rowY += rowStep;

		addDoubleSlider(
			ctrlX, rowY, ctrlW,
			"NPC Interaction Reach", cfg.customerInteractionDistance, 1.5, 5.0, "%.1fm",
			val -> cfg.customerInteractionDistance = val
		);
		rowY += rowStep;

		// HUD Show/Hide + Reset HUD Position row
		int halfW = (ctrlW - 4) / 2;
		addToggleButton(
			ctrlX, rowY, halfW,
			"HUD", cfg.showHudOverlay,
			val -> cfg.showHudOverlay = val
		);
		this.addRenderableWidget(Button.builder(Component.literal("§bReset HUD Pos"), b -> {
			cfg.hudX = 10;
			cfg.hudY = 10;
			cfg.save();
		}).bounds(ctrlX + halfW + 4, rowY, halfW, 17).build());

		// 4. Bottom Action Footer Buttons
		int bottomY = this.height - 25;
		this.addRenderableWidget(Button.builder(Component.literal("§7↺ Reset Defaults"), b -> {
			cfg.resetToDefaults();
			DeliveryLocationStorage.getInstance().resetHomesToBaseline();
			this.rebuildWidgets();
		}).bounds(leftX, bottomY, colW, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal("§b✔ Save & Close"), b -> this.onClose())
			.bounds(rightColX, bottomY, colW, 18).build());
	}

	private static Component formatNavModeLabel(NavigationMode mode) {
		if (mode == NavigationMode.MULTI_HOME_MODE) {
			return Component.literal("§fNav Mode: §d§lMulti-Home (/home h1-h4)");
		}
		return Component.literal("§fNav Mode: §6§lNether (/home nether)");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, this.width, this.height, 0xA8060911, 0xC8090D16);

		Minecraft mc = Minecraft.getInstance();
		DeliveryAutomationController ctrl = DeliveryAutomationController.getInstance();
		AutomationState state = ctrl.getState();
		DeliveryOrder order = ctrl.getCurrentOrder();
		CustomerTarget target = ctrl.getCustomerTarget();
		double dist = ctrl.getCurrentDistanceToCustomer(mc);

		int centerX = this.width / 2;
		int panelW = Math.min(392, this.width - 16);
		int leftX = centerX - panelW / 2;
		int colW = (panelW - 8) / 2;
		int rightColX = leftX + colW + 8;

		int accentColor = switch (state) {
			case IDLE -> 0xFF64748B;
			case PAUSED -> 0xFFF59E0B;
			case ERROR -> 0xFFEF4444;
			case DELIVERY_COMPLETED -> 0xFF10B981;
			default -> 0xFF38BDF8;
		};

		// Header Banner
		graphics.fillGradient(leftX, 6, leftX + panelW, 26, 0xEE0F172A, 0xEE1E293B);
		graphics.outline(leftX, 6, panelW, 20, 0xFF334155);
		graphics.fill(leftX, 6, leftX + panelW, 8, accentColor);
		graphics.text(
			this.font,
			"§b§l⚡ DELIVERY BOT DASHBOARD §8• §7Tip: Open Chat [T] to drag HUD anywhere!",
			leftX + 8,
			12,
			0xFFFFFFFF,
			true
		);

		int cardTopY = 52;
		int cardBottomY = this.height - 29;
		int cardH = Math.max(156, cardBottomY - cardTopY);

		// Left Column Card: Earnings, Telemetry & 4 Nether Homes Calculator
		graphics.fillGradient(leftX, cardTopY, leftX + colW, cardTopY + cardH, 0xE60F172A, 0xE60B1120);
		graphics.outline(leftX, cardTopY, colW, cardH, 0xFF1E293B);
		graphics.fill(leftX, cardTopY, leftX + colW, cardTopY + 2, 0xFF38BDF8);
		graphics.text(this.font, "§b§lEARNINGS & 4 NETHER HOMES", leftX + 8, cardTopY + 6, 0xFFFFFFFF, true);
		graphics.horizontalLine(leftX + 6, leftX + colW - 7, cardTopY + 17, 0xFF1E293B);

		int tx = leftX + 8;
		int ty = cardTopY + 21;
		int lineStep = 10;

		// Earnings & Runtime Section
		graphics.text(
			this.font,
			String.format("§7Total Earned: §a§l%,d xu §8(Done: §f%d§8)", ctrl.getTotalEarnedXu(), ctrl.getCompletedDeliveries()),
			tx, ty, 0xFFFFFFFF, false
		);
		ty += lineStep;
		graphics.text(
			this.font,
			String.format("§7Run Time: §b%s §8| §7Avg: §e%,d xu/m", ctrl.getFormattedRunTime(), ctrl.getAverageXuPerMinute()),
			tx, ty, 0xFFFFFFFF, false
		);
		ty += lineStep;

		String jobStr = order != null
			? "§e" + order.orderId() + " " + DeliveryHudOverlay.formatDifficultyBadge(order.difficulty()) + " §7(" + order.formattedRewardSummary() + "§7)"
			: "§8None";
		graphics.text(this.font, "§7Job: " + trimToWidth(jobStr, colW - 40), tx, ty, 0xFFFFFFFF, false);
		ty += lineStep;

		String custStr = target != null
			? String.format("§a%s §7@ OW(%d, %d) -> Neth(%d, %d)", target.customerName(), target.x(), target.z(), target.netherX(), target.netherZ())
			: "§8None";
		graphics.text(this.font, "§7Target: " + trimToWidth(custStr, colW - 48), tx, ty, 0xFFFFFFFF, false);
		ty += lineStep;

		String distStr = dist >= 0 ? String.format("§e%.1fm", dist) : "§8-";
		graphics.text(this.font, "§7Status: §f" + trimToWidth(state.displayName() + " (" + distStr + "§f)", colW - 50), tx, ty, 0xFFFFFFFF, false);
		ty += lineStep + 3;

		graphics.horizontalLine(leftX + 6, leftX + colW - 7, ty - 2, 0xFF1E293B);

		// 4 Calculated Nether Coordinates Section
		DeliveryLocationStorage storage = DeliveryLocationStorage.getInstance();
		List<HomeHubCoordinate> homes = storage.getCalculatedHomes();
		int activeHome = ctrl.getSelectedHomeIndex();
		String calcStateLabel = DeliveryConfig.get().coordinateCalculationEnabled ? "§aCalc: ON" : "§eCalc: OFF";

		graphics.text(
			this.font,
			"§d§l4 NETHER HOMES §7(" + storage.getRecordedCount() + " pts | " + calcStateLabel + "§7):",
			tx, ty, 0xFFFFFFFF, false
		);
		ty += lineStep;

		for (HomeHubCoordinate h : homes) {
			if (ty + 22 > cardTopY + cardH) {
				break;
			}
			boolean isSelected = (activeHome == h.homeIndex());
			String prefix = isSelected ? "§a§l▶ " : "§7• ";
			String line = String.format(
				"%s§fH%d §8(/%s): §dX: %d, Z: %d §8[%d pts]",
				prefix, h.homeIndex(), h.setHomeCommand(), h.netherX(), h.netherZ(), h.clusterSize()
			);
			graphics.text(this.font, trimToWidth(line, colW - 14), tx, ty, 0xFFFFFFFF, false);
			ty += 9;
		}

		// Right Column Card: Navigation Mode & Settings
		graphics.fillGradient(rightColX, cardTopY, rightColX + colW, cardTopY + cardH, 0xE60F172A, 0xE60B1120);
		graphics.outline(rightColX, cardTopY, colW, cardH, 0xFF1E293B);
		graphics.fill(rightColX, cardTopY, rightColX + colW, cardTopY + 2, 0xFF10B981);
		graphics.text(this.font, "§a§lNAVIGATION MODE & SETTINGS", rightColX + 8, cardTopY + 6, 0xFFFFFFFF, true);
		graphics.horizontalLine(rightColX + 6, rightColX + colW - 7, cardTopY + 17, 0xFF1E293B);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private String trimToWidth(String text, int maxPixels) {
		if (text == null) {
			return "";
		}
		if (this.font.width(text) <= maxPixels) {
			return text;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (this.font.width(sb.toString() + c + "...") > maxPixels) {
				break;
			}
			sb.append(c);
		}
		return sb.append("...").toString();
	}

	@Override
	public void onClose() {
		DeliveryConfig.get().save();
		if (this.minecraft != null && this.minecraft.gui != null) {
			this.minecraft.gui.setScreen(this.parent);
		}
	}

	private void addToggleButton(int x, int y, int width, String label, boolean initial, Consumer<Boolean> setter) {
		final boolean[] state = {initial};
		Button btn = Button.builder(formatToggle(label, state[0]), b -> {
			state[0] = !state[0];
			setter.accept(state[0]);
			DeliveryConfig.get().save();
			b.setMessage(formatToggle(label, state[0]));
		}).bounds(x, y, width, 17).build();
		this.addRenderableWidget(btn);
	}

	private static Component formatToggle(String label, boolean enabled) {
		return Component.literal("§f" + label + ": " + (enabled ? "§a§lON" : "§c§lOFF"));
	}

	private void addDoubleSlider(
		int x, int y, int width, String label, double current, double min, double max, String fmt, Consumer<Double> setter
	) {
		double norm = Mth.clamp((current - min) / (max - min), 0.0, 1.0);
		AbstractSliderButton slider = new AbstractSliderButton(
			x, y, width, 17,
			Component.literal("§f" + label + ": §b" + String.format(fmt, current)),
			norm
		) {
			@Override
			protected void updateMessage() {
				double val = min + this.value * (max - min);
				this.setMessage(Component.literal("§f" + label + ": §b" + String.format(fmt, val)));
			}

			@Override
			protected void applyValue() {
				double val = min + this.value * (max - min);
				setter.accept(val);
				DeliveryConfig.get().save();
			}
		};
		this.addRenderableWidget(slider);
	}
}
