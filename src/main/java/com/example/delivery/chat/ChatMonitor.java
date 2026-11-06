package com.example.delivery.chat;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.model.CustomerTarget;
import com.example.delivery.util.TextUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/**
 * Monitors incoming chat messages, actionbar overlays, and HUD titles/subtitles
 * to detect server confirmations and extract customer coordinates dynamically.
 */
public final class ChatMonitor {
	private static final ChatMonitor INSTANCE = new ChatMonitor();

	/**
	 * Matches the exact coordinate announcement from the server (Video 1 @ 11.5s):
	 * {@code [🧭] Khách Chị Tư đang chờ ở X -7272 Y 153 Z 1944 (world). Cầm gói hàng bấm vào khách. La bàn đã chỉ về đó.}
	 */
	private static final Pattern CUSTOMER_COORDS_PRIMARY = Pattern.compile(
		"Khách\\s+(.+?)\\s+đang\\s+chờ\\s+ở\\s+X\\s*[:=]?\\s*(-?\\d+)\\s*[, ]?\\s*Y\\s*[:=]?\\s*(-?\\d+)\\s*[, ]?\\s*Z\\s*[:=]?\\s*(-?\\d+)(?:\\s*\\(([^)]+)\\))?",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	/**
	 * Fallback pattern for variations in coordinate formatting.
	 */
	private static final Pattern CUSTOMER_COORDS_FALLBACK = Pattern.compile(
		"(?:Khách(?:\\s+hàng)?|Giao\\s+cho)\\s*[:\\-]?\\s*([^\\n\\r,:]+?)\\s+(?:tại|ở|tọa\\s+độ|\\-|,)\\s*X\\s*[:=]?\\s*(-?\\d+)\\s*[, ]?\\s*Y\\s*[:=]?\\s*(-?\\d+)\\s*[, ]?\\s*Z\\s*[:=]?\\s*(-?\\d+)(?:\\s*\\(([^)]+)\\))?",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	/**
	 * Matches order acceptance confirmation in chat (Video 1 @ 6.6s):
	 * {@code [📦] Đã nhận đơn Bánh bí ngô Ngon ★★. Hãy đến TIỆM BÁNH để lấy hàng (tiền vốn 437,500 xu, trừ khi lấy hàng).}
	 */
	private static final Pattern ORDER_ACCEPTED_PATTERN = Pattern.compile(
		"Đã\\s+nhận\\s+đơn\\s+(.+?)\\.\\s*Hãy\\s+đến",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	/**
	 * Matches RTP completion coordinates in chat or subtitle (Video 1 @ 22.0s):
	 * {@code [RTP] Bạn đã được dịch chuyển đến x=-5731 y=85 z=7595}
	 */
	private static final Pattern RTP_TELEPORTED_COORDS = Pattern.compile(
		"x\\s*=\\s*(-?\\d+)\\s+y\\s*=\\s*(-?\\d+)\\s+z\\s*=\\s*(-?\\d+)",
		Pattern.CASE_INSENSITIVE
	);

	/**
	 * Matches completed delivery reward in chat (Video 2 @ 9.5s & Video 5 @ 11.5s):
	 * {@code [✔] Ông Chín: "Cảm ơn bạn nhiều!" Nhận lương: 18,157,440 xu}
	 */
	private static final Pattern EARNED_SALARY_PATTERN = Pattern.compile(
		"Nhận\\s+lương\\s*:\\s*([0-9.,]+)\\s*xu",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	/**
	 * Matches expected payout announced when picking up package (Video 4 @ 2.5s):
	 * {@code [📦] Đã lấy hàng (vốn 281,250 xu). Khách cách khoảng 8,312 block, lương khi giao xong: 18,157,440 xu.}
	 */
	private static final Pattern EXPECTED_PAYOUT_PATTERN = Pattern.compile(
		"lương\\s+khi\\s+giao\\s+xong\\s*:\\s*([0-9.,]+)\\s*xu",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	/**
	 * Matches HUD subtitle reward announcement (Video 5 @ 11.5s):
	 * {@code +18,157,440 xu}
	 */
	private static final Pattern SUBTITLE_REWARD_PATTERN = Pattern.compile(
		"\\+\\s*([0-9.,]+)\\s*xu",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
	);

	private volatile boolean warpConfirmed = false;
	private volatile boolean homeNetherConfirmed = false;
	private volatile boolean orderAcceptedConfirmed = false;
	private volatile String acceptedOrderItemFromChat = "";
	private volatile boolean packageCollectedConfirmed = false;
	private volatile CustomerTarget latestCustomerTarget = null;
	private volatile boolean rtpWarmupActive = false;
	private volatile boolean rtpCompleted = false;
	private volatile boolean rtpFailedOrCancelled = false;
	private volatile boolean deliverySuccessConfirmed = false;
	private volatile long latestDeliveryEarnedXu = 0L;
	private volatile long expectedOrderPayoutXu = 0L;
	private volatile boolean wrongHandItemWarning = false;
	private volatile boolean noOrdersAvailableWarning = false;
	private volatile boolean actionBarSaysDifferentWorld = false;
	private volatile String latestActionBarText = "";
	private volatile String latestTitleText = "";
	private volatile String latestSubtitleText = "";

	private ChatMonitor() {
	}

	public static ChatMonitor getInstance() {
		return INSTANCE;
	}

	/**
	 * Resets all per-order flags at the beginning of a new delivery cycle so previous order state
	 * is never reused.
	 */
	public synchronized void resetOrderState() {
		this.warpConfirmed = false;
		this.homeNetherConfirmed = false;
		this.orderAcceptedConfirmed = false;
		this.acceptedOrderItemFromChat = "";
		this.packageCollectedConfirmed = false;
		this.latestCustomerTarget = null;
		this.rtpWarmupActive = false;
		this.rtpCompleted = false;
		this.rtpFailedOrCancelled = false;
		this.deliverySuccessConfirmed = false;
		this.latestDeliveryEarnedXu = 0L;
		this.expectedOrderPayoutXu = 0L;
		this.wrongHandItemWarning = false;
		this.noOrdersAvailableWarning = false;
		this.actionBarSaysDifferentWorld = false;
	}

	public synchronized void resetRtpAttemptFlags() {
		this.rtpWarmupActive = false;
		this.rtpCompleted = false;
		this.rtpFailedOrCancelled = false;
	}

	public synchronized void resetDeliveryAttemptFlags() {
		this.wrongHandItemWarning = false;
	}

	public void onChatMessage(Component component) {
		if (component == null) {
			return;
		}
		onChatMessageText(TextUtils.stripFormatting(component));
	}

	public synchronized void onChatMessageText(String plain) {
		if (plain == null || plain.isBlank()) {
			return;
		}
		String lower = TextUtils.normalizeLower(plain);

		// 1. Bakery home / warp confirmation (/home lambanh or /warp tuban)
		if (lower.contains("teleporting to lambanh")
			|| lower.contains("dịch chuyển đến lambanh")
			|| lower.contains("warping to tuban")
			|| lower.contains("dịch chuyển đến tuban")) {
			this.warpConfirmed = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Detected /home lambanh confirmation: {}", plain);
		}

		// 1b. Home Nether or Multi-Home (/home h1..h4, /home nether) teleport confirmation
		if (lower.contains("teleporting to nether")
			|| lower.contains("dịch chuyển đến nether")
			|| lower.matches(".*teleporting to h?[1-4]\\.?.*")
			|| lower.matches(".*dịch chuyển đến h?[1-4]\\.?.*")) {
			this.homeNetherConfirmed = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Detected navigation home teleport confirmation: {}", plain);
		}

		// 2. Order accepted confirmation (Video 1 @ 6.6s)
		Matcher orderMatcher = ORDER_ACCEPTED_PATTERN.matcher(plain);
		if (orderMatcher.find()) {
			this.orderAcceptedConfirmed = true;
			this.acceptedOrderItemFromChat = orderMatcher.group(1).trim();
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Order accepted via chat: item='{}'", this.acceptedOrderItemFromChat);
		} else if (lower.contains("đã nhận đơn") || lower.contains("hãy đến tiệm bánh để lấy hàng")) {
			this.orderAcceptedConfirmed = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Order accepted confirmation detected: {}", plain);
		}

		// 3. Customer coordinates extraction (Video 1 @ 11.5s)
		CustomerTarget parsedTarget = parseCustomerCoordinates(plain);
		if (parsedTarget != null) {
			this.latestCustomerTarget = parsedTarget;
			DeliveryBotMod.LOGGER.info(
				"[ChatMonitor] Extracted customer target: name='{}', X={}, Y={}, Z={}, world='{}'",
				parsedTarget.customerName(), parsedTarget.x(), parsedTarget.y(), parsedTarget.z(), parsedTarget.worldName()
			);
		}

		// 4. Package collected confirmation & expected payout (Video 4 @ 2.5s: "lương khi giao xong: 18,157,440 xu")
		if (lower.contains("đã lấy hàng") || lower.contains("cầm gói hàng bấm vào khách")) {
			this.packageCollectedConfirmed = true;
		}
		Matcher expectedMatcher = EXPECTED_PAYOUT_PATTERN.matcher(plain);
		if (expectedMatcher.find()) {
			long parsedExpected = parseXuDigits(expectedMatcher.group(1));
			if (parsedExpected > 0) {
				this.expectedOrderPayoutXu = parsedExpected;
			}
		}

		// 5. RTP warmup & completion (Video 1 @ 16.0s and 22.0s)
		if (lower.contains("[rtp]")) {
			if (lower.contains("sẽ dịch chuyển sau") || lower.contains("vui lòng không di chuyển")) {
				this.rtpWarmupActive = true;
				this.rtpCompleted = false;
				DeliveryBotMod.LOGGER.info("[ChatMonitor] RTP warmup started: standing still");
			} else if (lower.contains("bạn đã được dịch chuyển đến") || RTP_TELEPORTED_COORDS.matcher(plain).find()) {
				this.rtpWarmupActive = false;
				this.rtpCompleted = true;
				DeliveryBotMod.LOGGER.info("[ChatMonitor] RTP teleportation confirmed via chat: {}", plain);
			} else if (lower.contains("hủy") || lower.contains("di chuyển") || lower.contains("chờ") || lower.contains("cooldown") || lower.contains("không thể")) {
				this.rtpWarmupActive = false;
				this.rtpFailedOrCancelled = true;
				DeliveryBotMod.LOGGER.warn("[ChatMonitor] RTP failed/cancelled/cooldown message: {}", plain);
			}
		}

		// 6. Wrong hand item warning (Video 2 @ 7.2s: "[!] Hãy cầm gói hàng trên tay chính rồi bấm vào khách.")
		if (lower.contains("hãy cầm gói hàng trên tay chính")) {
			this.wrongHandItemWarning = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Server requested holding package in main hand");
		}

		// 7. Delivery completion confirmation & earned money extraction (Video 2 @ 9.5s & Video 5 @ 11.5s)
		// "[✔] Chị Tư: "Cảm ơn bạn nhiều!" Nhận lương: 17,283,945 xu + 1 Capsule"
		Matcher earnedMatcher = EARNED_SALARY_PATTERN.matcher(plain);
		if (earnedMatcher.find()) {
			long parsedEarned = parseXuDigits(earnedMatcher.group(1));
			if (parsedEarned > 0) {
				this.latestDeliveryEarnedXu = parsedEarned;
				DeliveryBotMod.LOGGER.info("[ChatMonitor] Extracted delivery earnings: {} xu", parsedEarned);
			}
		}

		if (lower.contains("giao hàng thành công")
			|| (lower.contains("cảm ơn bạn nhiều") && lower.contains("nhận lương"))
			|| (lower.contains("[⚡ shipper]") && lower.contains("kinh nghiệm"))) {
			this.deliverySuccessConfirmed = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Delivery completion confirmed via chat: {}", plain);
		}

		// 8. Check if server indicates no orders are available or insufficient funds
		if (lower.contains("hết đơn") || lower.contains("không còn đơn") || lower.contains("không đủ tiền vốn")) {
			this.noOrdersAvailableWarning = true;
		}
	}

	public synchronized void onOverlayMessage(Component overlay) {
		if (overlay == null) {
			return;
		}
		String plain = TextUtils.stripFormatting(overlay);
		this.latestActionBarText = plain;
		String lower = TextUtils.normalizeLower(plain);
		// Video 1 @ 11.5s: "🎁 Đến thế giới world để giao hàng cho Chị Tư"
		// Video 1 @ 22.0s: "🧭 Chị Tư - còn 5,857m ↑ ↑68m"
		if (lower.contains("đến thế giới")) {
			this.actionBarSaysDifferentWorld = true;
		} else if (lower.contains("còn ") && lower.contains("m")) {
			this.actionBarSaysDifferentWorld = false;
		}
	}

	public synchronized void onTitleSet(Component title) {
		if (title == null) {
			return;
		}
		String plain = TextUtils.stripFormatting(title);
		this.latestTitleText = plain;
		String lower = TextUtils.normalizeLower(plain);

		if (lower.contains("teleporting in")) {
			this.rtpWarmupActive = true;
			this.rtpCompleted = false;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Title indicates RTP warmup: {}", plain);
		} else if (lower.contains("teleported!")) {
			this.rtpWarmupActive = false;
			this.rtpCompleted = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Title indicates RTP completed: {}", plain);
		} else if (lower.contains("giao hàng thành công")) {
			this.deliverySuccessConfirmed = true;
			DeliveryBotMod.LOGGER.info("[ChatMonitor] Title indicates delivery success: {}", plain);
		}
	}

	public synchronized void onSubtitleSet(Component subtitle) {
		if (subtitle == null) {
			return;
		}
		String plain = TextUtils.stripFormatting(subtitle);
		this.latestSubtitleText = plain;
		if (RTP_TELEPORTED_COORDS.matcher(plain).find()) {
			this.rtpWarmupActive = false;
			this.rtpCompleted = true;
		}
		Matcher subReward = SUBTITLE_REWARD_PATTERN.matcher(plain);
		if (subReward.find()) {
			long parsedEarned = parseXuDigits(subReward.group(1));
			if (parsedEarned > 0) {
				this.latestDeliveryEarnedXu = parsedEarned;
			}
		}
	}

	private static long parseXuDigits(String raw) {
		if (raw == null) {
			return 0L;
		}
		String digits = raw.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return 0L;
		}
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	public long resolveCompletedOrderEarnings( long fallbackBaseRewardXu) {
		if (this.latestDeliveryEarnedXu > 0) {
			return this.latestDeliveryEarnedXu;
		}
		if (this.expectedOrderPayoutXu > 0) {
			return this.expectedOrderPayoutXu;
		}
		return Math.max(0L, fallbackBaseRewardXu);
	}

	/**
	 * Parses customer name, X, Y, Z, and world name from a chat line (stripped or raw).
	 */
	public static CustomerTarget parseCustomerCoordinates(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		String clean = TextUtils.stripFormatting(message).replaceAll("\\s+", " ").trim();

		Matcher primary = CUSTOMER_COORDS_PRIMARY.matcher(clean);
		if (primary.find()) {
			return buildTarget(primary);
		}

		Matcher fallback = CUSTOMER_COORDS_FALLBACK.matcher(clean);
		if (fallback.find()) {
			return buildTarget(fallback);
		}

		return null;
	}

	private static CustomerTarget buildTarget(Matcher matcher) {
		try {
			String name = matcher.group(1).trim();
			int x = Integer.parseInt(matcher.group(2).trim());
			int y = Integer.parseInt(matcher.group(3).trim());
			int z = Integer.parseInt(matcher.group(4).trim());
			String world = matcher.groupCount() >= 5 && matcher.group(5) != null
				? matcher.group(5).trim()
				: "world";
			if (!name.isEmpty()) {
				return new CustomerTarget(name, x, y, z, world);
			}
		} catch (NumberFormatException ignored) {
		}
		return null;
	}

	public boolean isWarpConfirmed() {
		return warpConfirmed;
	}

	public void clearWarpConfirmed() {
		this.warpConfirmed = false;
	}

	public boolean isHomeNetherConfirmed() {
		return homeNetherConfirmed;
	}

	public void clearHomeNetherConfirmed() {
		this.homeNetherConfirmed = false;
	}

	public boolean isOrderAcceptedConfirmed() {
		return orderAcceptedConfirmed;
	}

	public String getAcceptedOrderItemFromChat() {
		return acceptedOrderItemFromChat;
	}

	public boolean isPackageCollectedConfirmed() {
		return packageCollectedConfirmed;
	}

	public CustomerTarget getLatestCustomerTarget() {
		return latestCustomerTarget;
	}

	public boolean isRtpWarmupActive() {
		return rtpWarmupActive;
	}

	public boolean isRtpCompleted() {
		return rtpCompleted;
	}

	public boolean isRtpFailedOrCancelled() {
		return rtpFailedOrCancelled;
	}

	public boolean isDeliverySuccessConfirmed() {
		return deliverySuccessConfirmed;
	}

	public boolean isWrongHandItemWarning() {
		return wrongHandItemWarning;
	}

	public boolean isNoOrdersAvailableWarning() {
		return noOrdersAvailableWarning;
	}

	public boolean isActionBarSaysDifferentWorld() {
		return actionBarSaysDifferentWorld;
	}

	public String getLatestActionBarText() {
		return latestActionBarText;
	}

	public String getLatestTitleText() {
		return latestTitleText;
	}

	public String getLatestSubtitleText() {
		return latestSubtitleText;
	}
}