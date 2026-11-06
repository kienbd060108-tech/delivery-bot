package com.example.delivery.interaction;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.util.TextUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemLore;

/**
 * Handles GUI detection, tooltip inspection, dynamic order selection, bakery pickup interaction,
 * and inventory/hotbar package verification.
 */
public final class GuiInventoryHelper {
	public static final String DELIVERY_MENU_TITLE_KEY = "ĐIỆN THOẠI GIAO HÀNG";
	public static final String BAKERY_MENU_TITLE_KEY = "TIỆM BÁNH";

	private static final Pattern ORDER_HEADER_PATTERN = Pattern.compile(
		"ĐƠN\\s*(#?\\d+)(?:\\s*\\[([^\\]]+)\\])?",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private static final Pattern ORDER_ITEM_PATTERN = Pattern.compile(
		"Hàng\\s*:\\s*(\\d+)\\s*x\\s*(.+)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private static final Pattern ORDER_CAPITAL_PATTERN = Pattern.compile(
		"Tiền\\s+vốn(?:\\s+lấy\\s+hàng)?\\s*:\\s*([0-9.,]+)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private static final Pattern ORDER_SALARY_PATTERN = Pattern.compile(
		"Lương\\s*:\\s*([0-9.,]+)\\s*xu",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private static final Pattern ORDER_CAPSULE_PATTERN = Pattern.compile(
		"Thưởng\\s+thêm\\s*:\\s*(\\d+)\\s*Capsule",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private static final Pattern ORDER_DISTANCE_PATTERN = Pattern.compile(
		"Khách\\s+cách\\s+bạn\\s*:\\s*(.+)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private GuiInventoryHelper() {
	}

	/**
	 * Returns true if the currently open screen is a container GUI whose title contains {@code ĐIỆN THOẠI GIAO HÀNG}.
	 */
	public static boolean isDeliveryPhoneScreenOpen(Minecraft mc) {
		AbstractContainerScreen<?> screen = getOpenContainerScreen(mc);
		if (screen == null) {
			return false;
		}
		String title = TextUtils.stripFormatting(screen.getTitle());
		return TextUtils.containsIgnoreCase(title, DELIVERY_MENU_TITLE_KEY);
	}

	/**
	 * Returns true if the currently open screen is a container GUI whose title contains {@code TIỆM BÁNH}.
	 */
	public static boolean isBakeryScreenOpen(Minecraft mc) {
		AbstractContainerScreen<?> screen = getOpenContainerScreen(mc);
		if (screen == null) {
			return false;
		}
		String title = TextUtils.stripFormatting(screen.getTitle());
		return TextUtils.containsIgnoreCase(title, BAKERY_MENU_TITLE_KEY);
	}

	public static AbstractContainerScreen<?> getOpenContainerScreen(Minecraft mc) {
		if (mc == null || mc.gui == null) {
			return null;
		}
		Screen screen = mc.gui.screen();
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			return containerScreen;
		}
		return null;
	}

	/**
	 * Scans all upper container slots in the open {@code ĐIỆN THOẠI GIAO HÀNG} GUI and returns all valid
	 * orders (both already-accepted and selectable orders), ignoring barrier/cancel/decorative items.
	 */
	public static List<DeliveryOrder> scanValidOrdersInGui(Minecraft mc) {
		List<DeliveryOrder> orders = new ArrayList<>();
		AbstractContainerScreen<?> screen = getOpenContainerScreen(mc);
		if (screen == null || mc.player == null) {
			return orders;
		}
		AbstractContainerMenu menu = screen.getMenu();
		Inventory playerInv = mc.player.getInventory();

		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			// Never inspect or click player inventory slots when selecting an order
			if (slot.container == playerInv) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty() || stack.is(Items.BARRIER)) {
				continue;
			}
			List<String> lines = extractItemTextLines(mc, stack);
			DeliveryOrder parsed = parseOrderFromLines(i, lines);
			if (parsed != null) {
				orders.add(parsed);
			}
		}
		return orders;
	}

	/**
	 *Comparator that ranks jobs by highest difficulty tier (e.g. {@code Siêu khó > Khó > Thường > Dễ}),
	 * then highest base reward in xu, then bonus capsules, then capital cost, and finally order ID.
	 */
	public static final Comparator<DeliveryOrder> HARDEST_AND_HIGHEST_REWARD_COMPARATOR = Comparator
		.comparingInt(DeliveryOrder::difficultyRank)
		.thenComparingLong(DeliveryOrder::baseRewardXu)
		.thenComparingInt(DeliveryOrder::bonusCapsules)
		.thenComparingLong(DeliveryOrder::capitalCostXu)
		.thenComparingInt(GuiInventoryHelper::extractNumericOrderId);

	/**
	 * Chooses the best order from the scanned GUI orders:
	 * <ol>
	 *   <li>If an order is already accepted ({@code ĐÃ NHẬN}), returns that order immediately so we don't click again.</li>
	 *   <li>Otherwise, selects the most difficult available order with the highest reward to maximize earnings.</li>
	 * </ol>
	 */
	public static DeliveryOrder pickOrderToProcess(List<DeliveryOrder> orders, boolean preferHardest) {
		if (orders == null || orders.isEmpty()) {
			return null;
		}
		for (DeliveryOrder order : orders) {
			if (order.alreadyAccepted()) {
				return order;
			}
		}
		if (preferHardest) {
			return orders.stream()
				.max(HARDEST_AND_HIGHEST_REWARD_COMPARATOR)
				.orElse(orders.getFirst());
		}
		return orders.stream()
			.min(HARDEST_AND_HIGHEST_REWARD_COMPARATOR)
			.orElse(orders.getFirst());
	}

	private static int extractNumericOrderId(DeliveryOrder order) {
		if (order == null || order.orderId() == null) {
			return 0;
		}
		String digits = order.orderId().replaceAll("\\D+", "");
		try {
			return digits.isEmpty() ? 0 : Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Parses a single slot's stripped tooltip lines into a {@link DeliveryOrder}, or returns {@code null}
	 * if the slot is not a valid delivery order.
	 */
	public static DeliveryOrder parseOrderFromLines(int slotIndex, List<String> rawLines) {
		if (rawLines == null || rawLines.isEmpty()) {
			return null;
		}
		List<String> cleanLines = new ArrayList<>(rawLines.size());
		for (String line : rawLines) {
			String stripped = TextUtils.stripFormatting(line);
			if (!stripped.isEmpty()) {
				cleanLines.add(stripped);
			}
		}
		if (cleanLines.isEmpty()) {
			return null;
		}

		String header = cleanLines.getFirst();
		String headerLower = TextUtils.normalizeLower(header);
		if (headerLower.contains("hủy") || headerLower.contains("đóng") || headerLower.contains("thoát")) {
			return null;
		}

		Matcher headerMatcher = ORDER_HEADER_PATTERN.matcher(header);
		if (!headerMatcher.find()) {
			return null;
		}

		String rawId = headerMatcher.group(1).trim();
		String orderId = rawId.startsWith("#") ? rawId : "#" + rawId;
		String difficulty = headerMatcher.group(2) != null ? headerMatcher.group(2).trim() : "";

		String itemName = "";
		int quantity = 1;
		long capitalCost = 0L;
		long baseRewardXu = 0L;
		int bonusCapsules = 0;
		String distanceRange = "";
		boolean canAccept = false;
		boolean alreadyAccepted = false;

		for (int i = 1; i < cleanLines.size(); i++) {
			String line = cleanLines.get(i);
			String lower = TextUtils.normalizeLower(line);

			Matcher itemMatcher = ORDER_ITEM_PATTERN.matcher(line);
			if (itemMatcher.find()) {
				try {
					quantity = Integer.parseInt(itemMatcher.group(1).trim());
				} catch (NumberFormatException ignored) {
					quantity = 1;
				}
				itemName = itemMatcher.group(2).trim();
				continue;
			}

			Matcher capMatcher = ORDER_CAPITAL_PATTERN.matcher(line);
			if (capMatcher.find()) {
				String digits = capMatcher.group(1).replaceAll("[^0-9]", "");
				if (!digits.isEmpty()) {
					try {
						capitalCost = Long.parseLong(digits);
					} catch (NumberFormatException ignored) {
					}
				}
				continue;
			}

			Matcher salaryMatcher = ORDER_SALARY_PATTERN.matcher(line);
			if (salaryMatcher.find()) {
				String digits = salaryMatcher.group(1).replaceAll("[^0-9]", "");
				if (!digits.isEmpty()) {
					try {
						baseRewardXu = Long.parseLong(digits);
					} catch (NumberFormatException ignored) {
					}
				}
				continue;
			}

			Matcher capsuleMatcher = ORDER_CAPSULE_PATTERN.matcher(line);
			if (capsuleMatcher.find()) {
				try {
					bonusCapsules = Integer.parseInt(capsuleMatcher.group(1).trim());
				} catch (NumberFormatException ignored) {
				}
				continue;
			}

			Matcher distMatcher = ORDER_DISTANCE_PATTERN.matcher(line);
			if (distMatcher.find()) {
				distanceRange = distMatcher.group(1).trim();
				continue;
			}

			if (lower.contains("bấm trái") && lower.contains("nhận đơn")) {
				canAccept = true;
			}
			if (lower.contains("đã nhận") && (lower.contains("cửa hàng") || lower.contains("lấy hàng"))) {
				alreadyAccepted = true;
			}
		}

		if (itemName.isEmpty() || (!canAccept && !alreadyAccepted)) {
			return null;
		}

		return new DeliveryOrder(
			orderId,
			difficulty,
			itemName,
			quantity,
			capitalCost,
			baseRewardXu,
			bonusCapsules,
			distanceRange,
			slotIndex,
			alreadyAccepted,
			List.copyOf(cleanLines)
		);
	}

	/**
	 * Left-clicks the given container slot in the currently open container menu.
	 */
	public static boolean leftClickContainerSlot(Minecraft mc, int slotIndex) {
		AbstractContainerScreen<?> screen = getOpenContainerScreen(mc);
		if (screen == null || mc.gameMode == null || mc.player == null) {
			return false;
		}
		AbstractContainerMenu menu = screen.getMenu();
		if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
			return false;
		}
		Slot slot = menu.slots.get(slotIndex);
		if (slot.container == mc.player.getInventory()) {
			DeliveryBotMod.LOGGER.warn("[GuiInventoryHelper] Refusing to click player inventory slot {}", slotIndex);
			return false;
		}
		mc.gameMode.handleContainerInput(
			menu.containerId,
			slotIndex,
			0, // Left click (button 0) - never right-click (button 1 cancels order!)
			ContainerInput.PICKUP,
			mc.player
		);
		return true;
	}

	/**
	 * Finds the shipper package collection slot inside the {@code TIỆM BÁNH} GUI.
	 *
	 * <p>In Video 1 @ 9.7s, this is slot 10 ({@code chest_minecart}) titled {@code 🚚 LẤY HÀNG (Shipper)}
	 * with lore {@code Nhận đồ ăn của đơn bạn đã nhận trong điện thoại /giaohang}.</p>
	 */
	public static int findBakeryCollectSlot(Minecraft mc, DeliveryOrder activeOrder) {
		AbstractContainerScreen<?> screen = getOpenContainerScreen(mc);
		if (screen == null || mc.player == null) {
			return -1;
		}
		AbstractContainerMenu menu = screen.getMenu();
		Inventory playerInv = mc.player.getInventory();

		int fallbackItemMatchSlot = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container == playerInv) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty() || stack.is(Items.BARRIER)) {
				continue;
			}
			List<String> lines = extractItemTextLines(mc, stack);
			if (isBakeryShipperCollectButton(lines)) {
				return i;
			}
			if (activeOrder != null && !activeOrder.itemName().isBlank()) {
				for (String line : lines) {
					if (TextUtils.containsIgnoreCase(line, activeOrder.itemName())) {
						fallbackItemMatchSlot = i;
					}
				}
			}
		}
		return fallbackItemMatchSlot;
	}

	public static boolean isBakeryShipperCollectButton(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return false;
		}
		for (String line : lines) {
			String lower = TextUtils.normalizeLower(line);
			if ((lower.contains("lấy hàng") && lower.contains("shipper"))
				|| lower.contains("nhận đồ ăn của đơn")
				|| lower.contains("điện thoại /giaohang")) {
				return true;
			}
		}
		String firstLower = TextUtils.normalizeLower(lines.getFirst());
		return firstLower.contains("lấy hàng");
	}

	/**
	 * Checks whether the player's inventory or hotbar contains the delivery package matching {@code order}.
	 */
	public static boolean hasDeliveryPackage(Minecraft mc, DeliveryOrder order) {
		return findDeliveryPackageSlot(mc, order) != -1;
	}

	/**
	 * Finds the inventory slot index (0..8 for hotbar, 9..35 for main inventory) containing the delivery package.
	 */
	public static int findDeliveryPackageSlot(Minecraft mc, DeliveryOrder order) {
		if (mc == null || mc.player == null) {
			return -1;
		}
		Inventory inv = mc.player.getInventory();
		int genericPackageSlot = -1;

		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			List<String> lines = extractItemTextLines(mc, stack);
			if (matchesDeliveryPackage(lines, order)) {
				return i;
			}
			// Fallback if the item is explicitly named "Gói hàng [...]"
			if (genericPackageSlot == -1 && isAnyDeliveryPackage(lines)) {
				genericPackageSlot = i;
			}
		}
		return genericPackageSlot;
	}

	/**
	 * Verifies whether an item's display name and lore lines match the required delivery package.
	 *
	 * <p>In Video 1 @ 11.5s, accepting order {@code Bánh bí ngô Ngon ★★} produces an item named
	 * {@code Gói hàng [Bánh bí ngô Ngon ★★]}.</p>
	 */
	public static boolean matchesDeliveryPackage(List<String> itemLines, DeliveryOrder order) {
		if (itemLines == null || itemLines.isEmpty()) {
			return false;
		}
		String combined = String.join(" | ", itemLines);
		String lower = TextUtils.normalizeLower(combined);

		if (order != null && order.itemName() != null && !order.itemName().isBlank()) {
			String targetItemLower = TextUtils.normalizeLower(order.itemName());
			if (lower.contains("gói hàng") && lower.contains(targetItemLower)) {
				return true;
			}
			// Also check if the first line (display name) directly equals or contains "Gói hàng [<itemName>]"
			String headerLower = TextUtils.normalizeLower(itemLines.getFirst());
			if (headerLower.contains(targetItemLower) && !headerLower.startsWith("đơn #")) {
				return true;
			}
		}
		return false;
	}

	public static boolean isAnyDeliveryPackage(List<String> itemLines) {
		if (itemLines == null || itemLines.isEmpty()) {
			return false;
		}
		String headerLower = TextUtils.normalizeLower(itemLines.getFirst());
		return headerLower.contains("gói hàng [") || headerLower.startsWith("gói hàng");
	}

	/**
	 * Ensures the delivery package is equipped in the player's main hand (hotbar selected slot).
	 * Swaps from main inventory (slots 9..35) into the hotbar if necessary. Never drops items.
	 */
	public static boolean ensurePackageInMainHand(Minecraft mc, DeliveryOrder order) {
		if (mc == null || mc.player == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		ItemStack mainHand = player.getMainHandItem();
		if (!mainHand.isEmpty()) {
			List<String> mainLines = extractItemTextLines(mc, mainHand);
			if (matchesDeliveryPackage(mainLines, order) || isAnyDeliveryPackage(mainLines)) {
				return true;
			}
		}

		int slot = findDeliveryPackageSlot(mc, order);
		if (slot == -1) {
			return false;
		}

		Inventory inv = player.getInventory();
		if (slot >= 0 && slot < 9) {
			inv.setSelectedSlot(slot);
			return true;
		}

		// Package is in main inventory (slots 9..35): swap into hotbar using ContainerInput.SWAP
		if (mc.gameMode != null && player.inventoryMenu != null) {
			int targetHotbarSlot = findBestHotbarSlotForPackage(inv);
			mc.gameMode.handleContainerInput(
				player.inventoryMenu.containerId,
				slot,
				targetHotbarSlot,
				ContainerInput.SWAP,
				player
			);
			inv.setSelectedSlot(targetHotbarSlot);
			return true;
		}
		return false;
	}

	private static int findBestHotbarSlotForPackage(Inventory inv) {
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		return inv.getSelectedSlot();
	}

	/**
	 * Extracts all text lines (hover name, custom name, DataComponents.LORE, and tooltip lines)
	 * from an {@link ItemStack} with formatting codes stripped.
	 */
	public static List<String> extractItemTextLines(Minecraft mc, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		Set<String> uniqueLines = new LinkedHashSet<>();

		Component hoverName = stack.getHoverName();
		if (hoverName != null) {
			String s = TextUtils.stripFormatting(hoverName);
			if (!s.isEmpty()) {
				uniqueLines.add(s);
			}
		}

		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		if (customName != null) {
			String s = TextUtils.stripFormatting(customName);
			if (!s.isEmpty()) {
				uniqueLines.add(s);
			}
		}

		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null && lore.lines() != null) {
			for (Component line : lore.lines()) {
				String s = TextUtils.stripFormatting(line);
				if (!s.isEmpty()) {
					uniqueLines.add(s);
				}
			}
		}

		try {
			if (mc != null && mc.level != null && mc.player != null) {
				List<Component> tooltip = stack.getTooltipLines(
					Item.TooltipContext.of(mc.level),
					mc.player,
					TooltipFlag.NORMAL
				);
				for (Component line : tooltip) {
					String s = TextUtils.stripFormatting(line);
					if (!s.isEmpty()) {
						uniqueLines.add(s);
					}
				}
			}
		} catch (Throwable ignored) {
		}

		return new ArrayList<>(uniqueLines);
	}
}