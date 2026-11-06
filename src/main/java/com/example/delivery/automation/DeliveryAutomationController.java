package com.example.delivery.automation;

import com.example.delivery.DeliveryBotMod;
import com.example.delivery.chat.ChatMonitor;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.config.DeliveryConfig.NavigationMode;
import com.example.delivery.interaction.BlockBreakerHelper;
import com.example.delivery.interaction.GuiInventoryHelper;
import com.example.delivery.interaction.NetherPortalBuilder;
import com.example.delivery.interaction.NpcDetector;
import com.example.delivery.model.CustomerTarget;
import com.example.delivery.model.DeliveryOrder;
import com.example.delivery.navigation.BaritoneNavigator;
import com.example.delivery.navigation.DeliveryLocationStorage;
import com.example.delivery.navigation.DeliveryLocationStorage.HomeHubCoordinate;
import com.example.delivery.navigation.FlightNavigator;
import com.example.delivery.util.TextUtils;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Main tick-driven state machine orchestrating the complete automated delivery workflow:
 * <ol>
 *   <li>{@code /home lambanh} and open {@code /giaohang}</li>
 *   <li>Prioritize and select the most difficult available job ({@code Siêu khó > Khó > Thường > Dễ}) with the highest reward</li>
 *   <li>Use Baritone to navigate to {@code TIỆM BÁNH} and collect the delivery package</li>
 *   <li>Read customer Overworld coordinates {@code (X, Y, Z)}, calculate Nether {@code (X / 8, Z / 8)}, and persist to {@link DeliveryLocationStorage}</li>
 *   <li>Respect the selected {@link NavigationMode}:
 *       <ul>
 *         <li><b>Nether Mode:</b> Teleport via {@code /home nether}</li>
 *         <li><b>Multi-Home Mode:</b> Select the closest home among {@code 1, 2, 3, 4} and execute {@code /home <1-4>}</li>
 *       </ul>
 *   </li>
 *   <li>Use Baritone to travel to {@code (X / 8, Z / 8)}, build &amp; light a Nether portal, and return to the Overworld</li>
 *   <li>Use Baritone API ({@code allowBreak = true, autoTool = true}) to automatically navigate to the customer's exact coordinates and break any obstructing blocks</li>
 *   <li>Equip the package, right-click the customer NPC, verify {@code GIAO HÀNG THÀNH CÔNG}, record earnings, and repeat indefinitely</li>
 *   <li>If kicked from the server, automatically reconnect, execute {@code /giaohang huy} &rarr; {@code /giaohang huy xacnhan}, and restart the loop</li>
 * </ol>
 */
public final class DeliveryAutomationController {
	private static final DeliveryAutomationController INSTANCE = new DeliveryAutomationController();

	private final BlockBreakerHelper blockBreaker = new BlockBreakerHelper();
	private final NetherPortalBuilder portalBuilder = new NetherPortalBuilder();

	private AutomationState state = AutomationState.IDLE;
	private AutomationState stateBeforePause = AutomationState.IDLE;
	private int stateTicks = 0;

	private DeliveryOrder currentOrder = null;
	private CustomerTarget customerTarget = null;
	private int completedDeliveries = 0;
	private int deliveryRetryCount = 0;
	private int commandRetryCount = 0;
	private String lastErrorMessage = "None";
	private String statusDetail = "Ready";

	// Earnings & Runtime statistics
	private long totalEarnedXu = 0L;
	private long accumulatedRunMillis = 0L;
	private long activeSessionStartMillis = 0L;

	// Automatic Kick / Disconnect Recovery & Home Setup tracking
	private ServerData lastConnectedServerData = null;
	private int selectedHomeIndex = 0;
	private HomeHubCoordinate autoSetHomeTarget = null;

	// Internal per-state tracking
	private Vec3 lastTickPlayerPos = null;
	private int stablePosTicks = 0;
	private boolean orderClickSent = false;
	private int orderClickTicks = 0;
	private boolean bakeryCollectClickSent = false;
	private int bakeryInteractCooldown = 0;
	private int equipWaitTicks = 0;
	private int overworldArrivalTicks = 0;

	private DeliveryAutomationController() {
	}

	public static DeliveryAutomationController getInstance() {
		return INSTANCE;
	}

	/**
	 * Starts the automation from the beginning of a delivery cycle ({@code /home lambanh}).
	 */
	public void startAutomation(Minecraft mc) {
		if (mc == null || mc.player == null) {
			return;
		}
		if (mc.getCurrentServer() != null) {
			this.lastConnectedServerData = mc.getCurrentServer();
		}
		this.lastErrorMessage = "None";
		this.autoSetHomeTarget = null;
		startOrResumeTimer();
		resetCurrentOrderData(mc);
		transitionTo(mc, AutomationState.WARPING_TO_PICKUP, "Starting automated delivery -> /home lambanh");
		TextUtils.sendClientMessage(mc, "Automation STARTED. Teleporting to /home lambanh...", ChatFormatting.GREEN);
	}

	/**
	 * Starts the automated 3-step HUD Home Setup workflow for {@code H1}, {@code H2}, {@code H3}, or {@code H4}:
	 * <ol>
	 *   <li>Execute {@code /home nether}.</li>
	 *   <li>Automatically navigate via Baritone to the corresponding calculated coordinates {@code (netherX, netherZ)}.</li>
	 *   <li>Once the destination is reached, execute {@code /sethome h1}, {@code /sethome h2}, {@code /sethome h3}, or {@code /sethome h4}.</li>
	 * </ol>
	 */
	public void startAutoSetHome(Minecraft mc, int homeIndex) {
		if (mc == null || mc.player == null) {
			return;
		}
		HomeHubCoordinate targetHome = DeliveryLocationStorage.getInstance().getHomeByIndex(homeIndex);
		if (targetHome == null) {
			return;
		}
		if (mc.getCurrentServer() != null) {
			this.lastConnectedServerData = mc.getCurrentServer();
		}
		if (mc.gui != null && mc.gui.screen() != null) {
			mc.gui.setScreen(null);
		}
		this.lastErrorMessage = "None";
		resetCurrentOrderData(mc);
		this.autoSetHomeTarget = targetHome;
		this.selectedHomeIndex = targetHome.homeIndex();
		this.commandRetryCount = 0;

		String detail = String.format(
			"Auto-Setting H%d: /home nether -> (%d, %d) -> /%s",
			targetHome.homeIndex(),
			targetHome.netherX(),
			targetHome.netherZ(),
			targetHome.setHomeCommand()
		);
		transitionTo(mc, AutomationState.SETTING_HOME_TELEPORT_NETHER, detail);
		TextUtils.sendClientMessage(
			mc,
			String.format(
				"HUD Auto-Home H%d started: /home nether -> Baritone (%d, %d) -> /%s",
				targetHome.homeIndex(),
				targetHome.netherX(),
				targetHome.netherZ(),
				targetHome.setHomeCommand()
			),
			ChatFormatting.LIGHT_PURPLE
		);
	}

	/**
	 * Pauses the automation, stopping Baritone pathing and pausing the active runtime clock.
	 */
	public void pauseAutomation(Minecraft mc, String reason) {
		if (this.state == AutomationState.IDLE || this.state == AutomationState.PAUSED) {
			return;
		}
		pauseTimer();
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);
		this.stateBeforePause = this.state;
		this.state = AutomationState.PAUSED;
		this.stateTicks = 0;
		this.statusDetail = (reason != null && !reason.isBlank()) ? reason : "Paused by user";
		DeliveryBotMod.LOGGER.info("[DeliveryBot] Automation PAUSED (was {}): {}", this.stateBeforePause, this.statusDetail);
		if (mc != null && mc.player != null) {
			TextUtils.sendClientMessage(mc, "Automation PAUSED (" + this.statusDetail + ")", ChatFormatting.YELLOW);
		}
	}

	/**
	 * Resumes automation from the state that was active prior to pausing.
	 */
	public void resumeAutomation(Minecraft mc) {
		if (this.state != AutomationState.PAUSED) {
			if (this.state == AutomationState.IDLE || this.state == AutomationState.ERROR) {
				startAutomation(mc);
			}
			return;
		}
		startOrResumeTimer();
		AutomationState resumeTarget = (this.stateBeforePause != null && this.stateBeforePause.isActiveRunning())
			? this.stateBeforePause
			: AutomationState.WARPING_TO_PICKUP;
		this.lastErrorMessage = "None";
		transitionTo(mc, resumeTarget, "Resumed automation");
		if (mc != null && mc.player != null) {
			TextUtils.sendClientMessage(mc, "Automation RESUMED -> " + resumeTarget.displayName(), ChatFormatting.GREEN);
		}
	}

	/**
	 * Stops automation completely and cancels all Baritone and block-breaking actions.
	 */
	public void stopAutomation(Minecraft mc, String reason) {
		pauseTimer();
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);
		this.portalBuilder.reset();
		this.state = AutomationState.IDLE;
		this.stateBeforePause = AutomationState.IDLE;
		this.stateTicks = 0;
		this.statusDetail = (reason != null && !reason.isBlank()) ? reason : "Stopped";
		DeliveryBotMod.LOGGER.info("[DeliveryBot] Automation STOPPED: {}", this.statusDetail);
		if (mc != null && mc.player != null) {
			TextUtils.sendClientMessage(mc, "Automation STOPPED (" + this.statusDetail + ")", ChatFormatting.RED);
		}
	}

	/**
	 * Resets session earnings, delivery counter, and runtime clock to zero.
	 */
	public void resetSessionStatistics() {
		this.completedDeliveries = 0;
		this.totalEarnedXu = 0L;
		this.accumulatedRunMillis = 0L;
		this.activeSessionStartMillis = this.state.isActiveRunning() ? System.currentTimeMillis() : 0L;
	}

	private void startOrResumeTimer() {
		if (this.activeSessionStartMillis == 0L) {
			this.activeSessionStartMillis = System.currentTimeMillis();
		}
	}

	private void pauseTimer() {
		if (this.activeSessionStartMillis > 0L) {
			this.accumulatedRunMillis += Math.max(0L, System.currentTimeMillis() - this.activeSessionStartMillis);
			this.activeSessionStartMillis = 0L;
		}
	}

	/**
	 * Called when the player joins a server so we capture the {@link ServerData} for auto-reconnect
	 * and transition into the post-kick cancel &amp; restart flow if we were reconnecting.
	 */
	public void onJoinedServer(Minecraft mc) {
		if (mc != null && mc.getCurrentServer() != null) {
			this.lastConnectedServerData = mc.getCurrentServer();
		}
		if (this.state == AutomationState.RECONNECTING_AFTER_KICK) {
			DeliveryBotMod.LOGGER.info("[KickRecovery] Reconnected to server! Scheduling /giaohang huy -> /giaohang huy xacnhan");
			transitionTo(mc, AutomationState.RECOVERING_CANCEL_ORDER, "Reconnected; cancelling stale order (/giaohang huy)");
		}
	}

	/**
	 * Called when disconnected/kicked from the server.
	 * If automation was running and {@link DeliveryConfig#autoReconnectAndRecover} is enabled,
	 * automatically enters {@link AutomationState#RECONNECTING_AFTER_KICK}.
	 */
	public void onDisconnectedFromServer(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);

		if (this.state.isActiveRunning() && DeliveryConfig.get().autoReconnectAndRecover) {
			DeliveryBotMod.LOGGER.warn("[KickRecovery] Kicked/disconnected while automation was active! Entering auto-reconnect recovery...");
			this.state = AutomationState.RECONNECTING_AFTER_KICK;
			this.stateTicks = 0;
			this.statusDetail = "Kicked from server; reconnecting in 5s...";
		} else {
			stopAutomation(mc, "Disconnected from server");
		}
	}

	/**
	 * Toggles between Start and Stop when pressing the primary toggle keybind.
	 */
	public void toggleStartStop(Minecraft mc) {
		if (this.state == AutomationState.IDLE || this.state == AutomationState.ERROR) {
			startAutomation(mc);
		} else {
			stopAutomation(mc, "Stopped via keybind");
		}
	}

	/**
	 * Toggles between Pause and Resume when pressing the pause keybind.
	 */
	public void togglePauseResume(Minecraft mc) {
		if (this.state == AutomationState.PAUSED) {
			resumeAutomation(mc);
		} else if (this.state.isActiveRunning()) {
			pauseAutomation(mc, "Paused via keybind");
		}
	}

	/**
	 * Clears all per-order fields so a new cycle never reuses a previous order's item or coordinates.
	 */
	private void resetCurrentOrderData(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);
		this.portalBuilder.reset();
		this.currentOrder = null;
		this.customerTarget = null;
		this.deliveryRetryCount = 0;
		this.commandRetryCount = 0;
		this.selectedHomeIndex = 0;
		this.orderClickSent = false;
		this.orderClickTicks = 0;
		this.bakeryCollectClickSent = false;
		this.bakeryInteractCooldown = 0;
		this.equipWaitTicks = 0;
		this.overworldArrivalTicks = 0;
		this.stablePosTicks = 0;
		this.lastTickPlayerPos = null;
		ChatMonitor.getInstance().resetOrderState();
	}

	private void transitionTo(Minecraft mc, AutomationState nextState, String detail) {
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);
		DeliveryBotMod.LOGGER.info("[DeliveryBot] State: {} -> {} ({})", this.state, nextState, detail);
		this.state = nextState;
		this.stateTicks = 0;
		this.statusDetail = detail;
	}

	private void failWithError(Minecraft mc, String errorMsg) {
		pauseTimer();
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);
		this.lastErrorMessage = errorMsg;
		this.statusDetail = errorMsg;
		this.state = AutomationState.ERROR;
		this.stateTicks = 0;
		DeliveryBotMod.LOGGER.error("[DeliveryBot] ERROR: {}", errorMsg);
		if (mc != null && mc.player != null) {
			TextUtils.sendClientMessage(mc, "ERROR: " + errorMsg, ChatFormatting.RED);
		}
	}

	/**
	 * Called once per client tick (`END_CLIENT_TICK`). Never blocks the main thread.
	 */
	public void onClientTick(Minecraft mc) {
		if (this.state == AutomationState.IDLE
			|| this.state == AutomationState.PAUSED
			|| this.state == AutomationState.ERROR) {
			return;
		}
		if (mc == null) {
			return;
		}

		// Handle automatic reconnect even while disconnected on DisconnectedScreen / TitleScreen
		if (this.state == AutomationState.RECONNECTING_AFTER_KICK) {
			this.stateTicks++;
			tickReconnectingAfterKick(mc);
			return;
		}

		if (mc.level == null || mc.player == null) {
			if (DeliveryConfig.get().autoReconnectAndRecover) {
				onDisconnectedFromServer(mc);
			}
			return;
		}

		if (mc.getCurrentServer() != null) {
			this.lastConnectedServerData = mc.getCurrentServer();
		}

		if (mc.player.isDeadOrDying()) {
			pauseAutomation(mc, "Player died; paused safely");
			return;
		}

		this.stateTicks++;
		DeliveryConfig config = DeliveryConfig.get();

		switch (this.state) {
			case RECOVERING_CANCEL_ORDER -> tickRecoveringCancelOrder(mc);
			case WARPING_TO_PICKUP -> tickWarpingToPickup(mc);
			case WAITING_FOR_PICKUP -> tickWaitingForPickup(mc);
			case OPENING_DELIVERY_MENU -> tickOpeningDeliveryMenu(mc);
			case SELECTING_ORDER -> tickSelectingOrder(mc, config);
			case INTERACTING_WITH_BAKERY_NPC -> tickInteractingWithBakeryNpc(mc, config);
			case COLLECTING_PACKAGE -> tickCollectingPackage(mc);
			case READING_CUSTOMER_COORDINATES -> tickReadingCustomerCoordinates(mc, config);
			case TELEPORTING_HOME_NETHER -> tickTeleportingToNavigationHome(mc, config);
			case NAVIGATING_IN_NETHER -> tickNavigatingInNether(mc, config);
			case BUILDING_NETHER_PORTAL -> tickBuildingNetherPortal(mc);
			case ENTERING_NETHER_PORTAL -> tickEnteringNetherPortal(mc);
			case NAVIGATING_TO_CUSTOMER -> tickNavigatingToCustomer(mc, config);
			case LOCATING_CUSTOMER -> tickLocatingCustomer(mc, config);
			case DELIVERING_PACKAGE -> tickDeliveringPackage(mc, config);
			case VERIFYING_DELIVERY -> tickVerifyingDelivery(mc, config);
			case DELIVERY_COMPLETED -> tickDeliveryCompleted(mc);
			case STARTING_NEXT_ORDER -> tickStartingNextOrder(mc);
			case SETTING_HOME_TELEPORT_NETHER -> tickSettingHomeTeleportNether(mc);
			case SETTING_HOME_NAVIGATING -> tickSettingHomeNavigating(mc, config);
			case SETTING_HOME_EXECUTING -> tickSettingHomeExecuting(mc);
			default -> {
			}
		}
	}

	// =========================================================================
	// HUD HOME AUTOMATION (H1, H2, H3, H4):
	// 1. /home nether -> 2. Baritone navigate to (netherX, netherZ) -> 3. /sethome h1..h4
	// =========================================================================

	private void tickSettingHomeTeleportNether(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		if (this.autoSetHomeTarget == null) {
			failWithError(mc, "Missing target home coordinate for Auto-Home setup");
			return;
		}

		if (this.stateTicks == 1) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			ChatMonitor.getInstance().clearHomeNetherConfirmed();
			this.lastTickPlayerPos = mc.player.position();
			this.stablePosTicks = 0;
			DeliveryBotMod.LOGGER.info(
				"[AutoSetHome] Step 1/3: Sending /home nether for H{} ({}, {})",
				this.autoSetHomeTarget.homeIndex(), this.autoSetHomeTarget.netherX(), this.autoSetHomeTarget.netherZ()
			);
			mc.player.connection.sendCommand("home nether");
			this.statusDetail = String.format(
				"Auto H%d: Sent /home nether, waiting for teleport...",
				this.autoSetHomeTarget.homeIndex()
			);
			return;
		}

		Vec3 currentPos = mc.player.position();
		if (this.lastTickPlayerPos != null && currentPos.distanceTo(this.lastTickPlayerPos) < 0.04) {
			this.stablePosTicks++;
		} else {
			this.stablePosTicks = 0;
		}
		this.lastTickPlayerPos = currentPos;

		boolean inNetherDimension = isInNether(mc);
		boolean chatConfirmed = ChatMonitor.getInstance().isHomeNetherConfirmed();

		if ((inNetherDimension && this.stateTicks >= 15 && this.stablePosTicks >= 8)
			|| (chatConfirmed && inNetherDimension && this.stateTicks >= 10)) {
			this.commandRetryCount = 0;
			DeliveryBotMod.LOGGER.info(
				"[AutoSetHome] Step 2/3: Arrived in Nether. Baritone navigating to H{} ({}, {})",
				this.autoSetHomeTarget.homeIndex(), this.autoSetHomeTarget.netherX(), this.autoSetHomeTarget.netherZ()
			);
			transitionTo(
				mc,
				AutomationState.SETTING_HOME_NAVIGATING,
				String.format(
					"Baritone -> H%d (%d, %d)",
					this.autoSetHomeTarget.homeIndex(),
					this.autoSetHomeTarget.netherX(),
					this.autoSetHomeTarget.netherZ()
				)
			);
			return;
		}

		if (this.stateTicks >= 120) {
			if (this.commandRetryCount < 3) {
				this.commandRetryCount++;
				this.stateTicks = 0;
				this.statusDetail = "Retrying /home nether for H" + this.autoSetHomeTarget.homeIndex() + " (" + this.commandRetryCount + "/3)";
			} else {
				failWithError(mc, "Timed out waiting for /home nether teleport during H" + this.autoSetHomeTarget.homeIndex() + " setup");
			}
		}
	}

	private void tickSettingHomeNavigating(Minecraft mc, DeliveryConfig config) {
		if (this.autoSetHomeTarget == null) {
			failWithError(mc, "Missing target home coordinate during Baritone navigation");
			return;
		}

		boolean reached = BaritoneNavigator.navigateToNetherCoordsTick(
			mc,
			this.autoSetHomeTarget.netherX(),
			this.autoSetHomeTarget.netherZ(),
			config
		);

		double dist = this.autoSetHomeTarget.netherDistanceTo(mc.player.getX(), mc.player.getZ());
		this.statusDetail = String.format(
			"Auto H%d -> (%d, %d) | %.1fm left (%s)",
			this.autoSetHomeTarget.homeIndex(),
			this.autoSetHomeTarget.netherX(),
			this.autoSetHomeTarget.netherZ(),
			dist,
			BaritoneNavigator.getBaritoneStatusSummary()
		);

		if (reached) {
			BaritoneNavigator.stopBaritone(mc);
			DeliveryBotMod.LOGGER.info(
				"[AutoSetHome] Step 3/3: Reached H{} coordinates ({}, {}). Executing /{}",
				this.autoSetHomeTarget.homeIndex(),
				this.autoSetHomeTarget.netherX(),
				this.autoSetHomeTarget.netherZ(),
				this.autoSetHomeTarget.setHomeCommand()
			);
			transitionTo(
				mc,
				AutomationState.SETTING_HOME_EXECUTING,
				String.format("Reached H%d; executing /%s", this.autoSetHomeTarget.homeIndex(), this.autoSetHomeTarget.setHomeCommand())
			);
		}
	}

	private void tickSettingHomeExecuting(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		if (this.autoSetHomeTarget == null) {
			failWithError(mc, "Missing target home coordinate when executing /sethome");
			return;
		}

		if (this.stateTicks == 5) {
			String setHomeCmd = this.autoSetHomeTarget.setHomeCommand(); // "sethome h1" .. "sethome h4"
			DeliveryBotMod.LOGGER.info(
				"[AutoSetHome] Sending /{} at ({}, {})",
				setHomeCmd, (int) Math.round(mc.player.getX()), (int) Math.round(mc.player.getZ())
			);
			mc.player.connection.sendCommand(setHomeCmd);
			this.statusDetail = "Executed /" + setHomeCmd + " at (" + this.autoSetHomeTarget.netherX() + ", " + this.autoSetHomeTarget.netherZ() + ")";
		} else if (this.stateTicks >= 20) {
			HomeHubCoordinate completedHome = this.autoSetHomeTarget;
			this.autoSetHomeTarget = null;
			this.state = AutomationState.IDLE;
			this.stateTicks = 0;
			this.statusDetail = String.format(
				"Set /%s at (%d, %d)",
				completedHome.setHomeCommand(),
				completedHome.netherX(),
				completedHome.netherZ()
			);
			TextUtils.sendClientMessage(
				mc,
				String.format(
					"Completed H%d setup! Executed /%s at Nether (%d, %d).",
					completedHome.homeIndex(),
					completedHome.setHomeCommand(),
					completedHome.netherX(),
					completedHome.netherZ()
				),
				ChatFormatting.GREEN
			);
		}
	}

	// =========================================================================
	// AUTOMATIC RECOVERY AFTER BEING KICKED:
	// Reconnect -> /giaohang huy -> /giaohang huy xacnhan -> Restart loop
	// =========================================================================

	private void tickReconnectingAfterKick(Minecraft mc) {
		if (mc.level != null && mc.player != null) {
			transitionTo(mc, AutomationState.RECOVERING_CANCEL_ORDER, "Reconnected; running /giaohang huy recovery");
			return;
		}

		int ticksUntilAttempt = 100 - (this.stateTicks % 120);
		if (ticksUntilAttempt > 0) {
			this.statusDetail = String.format("Reconnecting to server in %.1fs...", ticksUntilAttempt / 20.0);
			return;
		}

		if (this.lastConnectedServerData == null || this.lastConnectedServerData.ip == null || this.lastConnectedServerData.ip.isBlank()) {
			failWithError(mc, "Cannot auto-reconnect: no saved server address");
			return;
		}

		if (mc.gui != null && (mc.gui.screen() instanceof DisconnectedScreen
			|| mc.gui.screen() instanceof TitleScreen
			|| mc.gui.screen() instanceof JoinMultiplayerScreen
			|| mc.gui.screen() == null)) {
			DeliveryBotMod.LOGGER.info("[KickRecovery] Connecting to {}...", this.lastConnectedServerData.ip);
			this.statusDetail = "Connecting to " + this.lastConnectedServerData.ip + "...";
			ServerAddress address = ServerAddress.parseString(this.lastConnectedServerData.ip);
			ConnectScreen.startConnecting(
				new JoinMultiplayerScreen(new TitleScreen()),
				mc,
				address,
				this.lastConnectedServerData,
				false,
				null
			);
		}
	}

	private void tickRecoveringCancelOrder(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);

		if (this.stateTicks == 40) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			DeliveryBotMod.LOGGER.info("[KickRecovery] Step 1/2: Sending /giaohang huy");
			mc.player.connection.sendCommand("giaohang huy");
			this.statusDetail = "Sent /giaohang huy, waiting to confirm...";
		} else if (this.stateTicks == 75) {
			DeliveryBotMod.LOGGER.info("[KickRecovery] Step 2/2: Sending /giaohang huy xacnhan");
			mc.player.connection.sendCommand("giaohang huy xacnhan");
			this.statusDetail = "Sent /giaohang huy xacnhan, restarting loop...";
		} else if (this.stateTicks >= 110) {
			resetCurrentOrderData(mc);
			DeliveryBotMod.LOGGER.info("[KickRecovery] Recovery complete! Restarting delivery automation loop.");
			TextUtils.sendClientMessage(
				mc,
				"Kick recovery complete (/giaohang huy -> /giaohang huy xacnhan). Restarting delivery loop!",
				ChatFormatting.GREEN
			);
			transitionTo(mc, AutomationState.WARPING_TO_PICKUP, "Restarting loop -> /home lambanh");
		}
	}

	// =========================================================================
	// STEP 1: /home lambanh -> wait for stabilization -> /giaohang
	// =========================================================================

	private void tickWarpingToPickup(Minecraft mc) {
		if (this.stateTicks == 1) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			ChatMonitor.getInstance().clearWarpConfirmed();
			this.lastTickPlayerPos = mc.player.position();
			this.stablePosTicks = 0;
			DeliveryBotMod.LOGGER.info("[Step 1] Sending /home lambanh");
			mc.player.connection.sendCommand("home lambanh");
			transitionTo(mc, AutomationState.WAITING_FOR_PICKUP, "Waiting for /home lambanh teleport");
		}
	}

	private void tickWaitingForPickup(Minecraft mc) {
		LocalPlayer player = mc.player;
		Vec3 currentPos = player.position();
		if (this.lastTickPlayerPos != null && currentPos.distanceTo(this.lastTickPlayerPos) < 0.04) {
			this.stablePosTicks++;
		} else {
			this.stablePosTicks = 0;
		}
		this.lastTickPlayerPos = currentPos;

		boolean warpConfirmed = ChatMonitor.getInstance().isWarpConfirmed();
		if ((warpConfirmed && this.stateTicks >= 25 && this.stablePosTicks >= 12)
			|| (this.stateTicks >= 55 && this.stablePosTicks >= 20)) {
			this.commandRetryCount = 0;
			transitionTo(mc, AutomationState.OPENING_DELIVERY_MENU, "Sending /giaohang");
			return;
		}

		if (this.stateTicks >= 160) {
			if (this.commandRetryCount < 3) {
				this.commandRetryCount++;
				transitionTo(mc, AutomationState.WARPING_TO_PICKUP, "Retrying /home lambanh (" + this.commandRetryCount + "/3)");
			} else {
				failWithError(mc, "Timed out waiting for /home lambanh confirmation");
			}
		}
	}

	private void tickOpeningDeliveryMenu(Minecraft mc) {
		if (GuiInventoryHelper.isDeliveryPhoneScreenOpen(mc)) {
			if (this.stateTicks >= 8) {
				this.commandRetryCount = 0;
				this.orderClickSent = false;
				this.orderClickTicks = 0;
				transitionTo(mc, AutomationState.SELECTING_ORDER, "Evaluating hardest & highest-reward job");
			}
			return;
		}

		if (this.stateTicks == 5) {
			DeliveryBotMod.LOGGER.info("[Step 1] Sending /giaohang");
			mc.player.connection.sendCommand("giaohang");
			this.statusDetail = "Waiting for ĐIỆN THOẠI GIAO HÀNG GUI";
		}

		if (this.stateTicks >= 100) {
			if (this.commandRetryCount < 3) {
				this.commandRetryCount++;
				this.stateTicks = 0;
				this.statusDetail = "Retrying /giaohang (" + this.commandRetryCount + "/3)";
			} else {
				failWithError(mc, "Failed to open ĐIỆN THOẠI GIAO HÀNG GUI after 3 attempts");
			}
		}
	}

	// =========================================================================
	// STEP 2: Always prioritize and select the most difficult available job
	//         to maximize earnings and receive the highest possible reward
	// =========================================================================

	private void tickSelectingOrder(Minecraft mc, DeliveryConfig config) {
		ChatMonitor chat = ChatMonitor.getInstance();

		if (this.orderClickSent && chat.isOrderAcceptedConfirmed()) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			DeliveryBotMod.LOGGER.info("[Step 2] Job confirmed: {}", this.currentOrder != null ? this.currentOrder.displaySummary() : "");
			transitionTo(mc, AutomationState.INTERACTING_WITH_BAKERY_NPC, "Job accepted; Baritone pathing to TIỆM BÁNH");
			return;
		}

		if (!GuiInventoryHelper.isDeliveryPhoneScreenOpen(mc)) {
			if (this.orderClickSent) {
				this.orderClickTicks++;
				if (chat.isOrderAcceptedConfirmed()) {
					transitionTo(mc, AutomationState.INTERACTING_WITH_BAKERY_NPC, "Job accepted; Baritone pathing to TIỆM BÁNH");
					return;
				}
				if (this.orderClickTicks > 30) {
					transitionTo(mc, AutomationState.OPENING_DELIVERY_MENU, "Re-opening /giaohang to verify order");
				}
				return;
			}
			transitionTo(mc, AutomationState.OPENING_DELIVERY_MENU, "Re-opening /giaohang");
			return;
		}

		List<DeliveryOrder> validOrders = GuiInventoryHelper.scanValidOrdersInGui(mc);
		if (validOrders.isEmpty()) {
			if (this.stateTicks < 35) {
				return;
			}
			mc.player.closeContainer();
			stopAutomation(mc, "No valid delivery jobs available in ĐIỆN THOẠI GIAO HÀNG");
			return;
		}

		DeliveryOrder chosen = GuiInventoryHelper.pickOrderToProcess(validOrders, config.preferHardestOrder);
		if (chosen == null) {
			mc.player.closeContainer();
			stopAutomation(mc, "No selectable job found");
			return;
		}

		if (chosen.alreadyAccepted()) {
			this.currentOrder = chosen;
			mc.player.closeContainer();
			DeliveryBotMod.LOGGER.info("[Step 2] Job {} is already active: {}", chosen.orderId(), chosen.displaySummary());
			transitionTo(mc, AutomationState.INTERACTING_WITH_BAKERY_NPC, "Job active; Baritone pathing to TIỆM BÁNH");
			return;
		}

		if (!this.orderClickSent) {
			this.currentOrder = chosen;
			boolean clicked = GuiInventoryHelper.leftClickContainerSlot(mc, chosen.slotIndex());
			if (clicked) {
				this.orderClickSent = true;
				this.orderClickTicks = 0;
				this.statusDetail = String.format(
					"Selected %s (%s)",
					chosen.displaySummary(),
					chosen.formattedRewardSummary()
				);
				DeliveryBotMod.LOGGER.info(
					"[Step 2] Selected hardest job slot {} -> {} (TierRank={}, BaseReward={} xu, Capsules={}, Cost={} xu)",
					chosen.slotIndex(), chosen.displaySummary(), chosen.difficultyRank(),
					chosen.baseRewardXu(), chosen.bonusCapsules(), chosen.capitalCostXu()
				);
			}
		} else {
			this.orderClickTicks++;
			if (chat.isNoOrdersAvailableWarning()) {
				mc.player.closeContainer();
				stopAutomation(mc, "Server reported order unavailable or insufficient capital");
				return;
			}
			if (this.orderClickTicks >= 45) {
				this.orderClickSent = false;
				this.orderClickTicks = 0;
			}
		}

		if (this.stateTicks >= 160) {
			mc.player.closeContainer();
			failWithError(mc, "Timed out trying to accept order in ĐIỆN THOẠI GIAO HÀNG");
		}
	}

	// =========================================================================
	// STEP 3: Use Baritone to navigate to TIỆM BÁNH NPC and collect package
	// =========================================================================

	private void tickInteractingWithBakeryNpc(Minecraft mc, DeliveryConfig config) {
		if (GuiInventoryHelper.isBakeryScreenOpen(mc)) {
			BaritoneNavigator.stopBaritone(mc);
			this.bakeryCollectClickSent = false;
			transitionTo(mc, AutomationState.COLLECTING_PACKAGE, "TIỆM BÁNH opened; collecting package");
			return;
		}

		if (this.bakeryInteractCooldown > 0) {
			this.bakeryInteractCooldown--;
		}

		Entity bakeryNpc = NpcDetector.findBakeryNpc(mc);
		if (bakeryNpc == null) {
			if (this.stateTicks < 80) {
				this.statusDetail = "Walking into CỬA HÀNG BÁNH to locate TIỆM BÁNH NPC";
				mc.options.keyUp.setDown(true);
				mc.options.keyJump.setDown(mc.player.horizontalCollision && mc.player.onGround());
				return;
			}
			BaritoneNavigator.stopBaritone(mc);
			if (this.commandRetryCount < 2) {
				this.commandRetryCount++;
				transitionTo(mc, AutomationState.WARPING_TO_PICKUP, "TIỆM BÁNH not found; re-teleporting to /home lambanh");
			} else {
				failWithError(mc, "Could not locate TIỆM BÁNH NPC near /home lambanh");
			}
			return;
		}

		boolean inRangeAndAimed = BaritoneNavigator.navigateToNpcTick(
			mc,
			bakeryNpc,
			config.customerInteractionDistance,
			config
		);

		if (inRangeAndAimed && this.bakeryInteractCooldown == 0) {
			DeliveryBotMod.LOGGER.info("[Step 3] Interacting with TIỆM BÁNH NPC (id={})", bakeryNpc.getId());
			NpcDetector.interactWithNpc(mc, bakeryNpc);
			this.bakeryInteractCooldown = 20;
			this.statusDetail = "Right-clicked TIỆM BÁNH; waiting for GUI";
		}

		if (this.stateTicks >= 240) {
			failWithError(mc, "Timed out trying to open TIỆM BÁNH NPC GUI");
		}
	}

	private void tickCollectingPackage(Minecraft mc) {
		if (GuiInventoryHelper.hasDeliveryPackage(mc, this.currentOrder)) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			DeliveryBotMod.LOGGER.info(
				"[Step 3] Confirmed delivery package in inventory for order {}",
				this.currentOrder != null ? this.currentOrder.displaySummary() : ""
			);
			transitionTo(mc, AutomationState.READING_CUSTOMER_COORDINATES, "Package collected; reading customer coordinates");
			return;
		}

		if (!GuiInventoryHelper.isBakeryScreenOpen(mc)) {
			if (this.bakeryCollectClickSent && this.stateTicks < 50) {
				return;
			}
			transitionTo(mc, AutomationState.INTERACTING_WITH_BAKERY_NPC, "Re-opening TIỆM BÁNH GUI to collect package");
			return;
		}

		if (this.stateTicks < 6) {
			return;
		}

		if (!this.bakeryCollectClickSent) {
			int collectSlot = GuiInventoryHelper.findBakeryCollectSlot(mc, this.currentOrder);
			if (collectSlot != -1) {
				boolean clicked = GuiInventoryHelper.leftClickContainerSlot(mc, collectSlot);
				if (clicked) {
					this.bakeryCollectClickSent = true;
					this.statusDetail = "Clicked LẤY HÀNG (Shipper) in TIỆM BÁNH";
					DeliveryBotMod.LOGGER.info("[Step 3] Left-clicked LẤY HÀNG (Shipper) slot {} in TIỆM BÁNH", collectSlot);
				}
			}
		} else if (this.stateTicks % 35 == 0) {
			this.bakeryCollectClickSent = false;
		}

		if (this.stateTicks >= 140) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			failWithError(mc, "Failed to receive delivery package from TIỆM BÁNH");
		}
	}

	// =========================================================================
	// STEP 4: Read customer's Overworld coordinates, save to DeliveryLocationStorage,
	//         recalculate 4 optimal Nether homes, and route via selected NavigationMode
	// =========================================================================

	private void tickReadingCustomerCoordinates(Minecraft mc, DeliveryConfig config) {
		CustomerTarget parsed = ChatMonitor.getInstance().getLatestCustomerTarget();
		if (parsed != null) {
			this.customerTarget = parsed;

			// Automatically save this delivery destination and recalculate the 4 optimal Nether homes
			DeliveryLocationStorage.getInstance().recordDeliveryDestination(parsed);

			HomeHubCoordinate nearestHome = DeliveryLocationStorage.getInstance().findNearestHome(parsed);
			this.selectedHomeIndex = nearestHome.homeIndex();

			String modeInfo = (config.navigationMode == NavigationMode.MULTI_HOME_MODE)
				? "Multi-Home -> /" + nearestHome.homeCommand() + " (Nether " + nearestHome.netherX() + ", " + nearestHome.netherZ() + ")"
				: "Nether Mode -> /home nether";

			DeliveryBotMod.LOGGER.info(
				"[Step 4] Customer destination locked: {} at {} -> Nether ({}, {}) | Routing: {}",
				parsed.customerName(), parsed.formattedCoordinates(), parsed.netherX(), parsed.netherZ(), modeInfo
			);
			TextUtils.sendClientMessage(
				mc,
				String.format(
					"Target: %s @ %s | Nether: (%d, %d) | %s",
					parsed.customerName(),
					parsed.formattedCoordinates(),
					parsed.netherX(),
					parsed.netherZ(),
					modeInfo
				),
				ChatFormatting.AQUA
			);
			this.commandRetryCount = 0;
			transitionTo(mc, AutomationState.TELEPORTING_HOME_NETHER, modeInfo);
			return;
		}

		if (this.stateTicks >= 100) {
			failWithError(mc, "Timed out waiting for customer coordinates in chat");
		}
	}

	// =========================================================================
	// STEP 5: Teleport via selected NavigationMode:
	//         - NETHER_MODE: /home nether
	//         - MULTI_HOME_MODE: /home h1, /home h2, /home h3, or /home h4 (closest home)
	// =========================================================================

	private void tickTeleportingToNavigationHome(Minecraft mc, DeliveryConfig config) {
		BaritoneNavigator.stopBaritone(mc);

		String commandToRun;
		if (config.navigationMode == NavigationMode.MULTI_HOME_MODE) {
			HomeHubCoordinate nearest = DeliveryLocationStorage.getInstance().findNearestHome(this.customerTarget);
			this.selectedHomeIndex = nearest.homeIndex();
			commandToRun = nearest.homeCommand(); // "home h1", "home h2", "home h3", or "home h4"
		} else {
			this.selectedHomeIndex = 0;
			commandToRun = "home nether";
		}

		if (this.stateTicks == 1) {
			if (mc.gui != null && mc.gui.screen() != null) {
				mc.player.closeContainer();
			}
			ChatMonitor.getInstance().clearHomeNetherConfirmed();
			this.lastTickPlayerPos = mc.player.position();
			this.stablePosTicks = 0;
			DeliveryBotMod.LOGGER.info("[Step 5] Sending /{} (Mode={})", commandToRun, config.navigationMode);
			mc.player.connection.sendCommand(commandToRun);
			this.statusDetail = "Sent /" + commandToRun + ", waiting for teleport...";
			return;
		}

		Vec3 currentPos = mc.player.position();
		if (this.lastTickPlayerPos != null && currentPos.distanceTo(this.lastTickPlayerPos) < 0.04) {
			this.stablePosTicks++;
		} else {
			this.stablePosTicks = 0;
		}
		this.lastTickPlayerPos = currentPos;

		boolean inNetherDimension = isInNether(mc);
		boolean chatConfirmed = ChatMonitor.getInstance().isHomeNetherConfirmed();

		if ((inNetherDimension && this.stateTicks >= 15 && this.stablePosTicks >= 8)
			|| (chatConfirmed && inNetherDimension && this.stateTicks >= 10)) {
			this.commandRetryCount = 0;
			DeliveryBotMod.LOGGER.info(
				"[Step 5] Arrived in Nether via /{} at ({}, {}, {}). Navigating to ({}, {}) via Baritone",
				commandToRun, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ(),
				this.customerTarget.netherX(), this.customerTarget.netherZ()
			);
			transitionTo(
				mc,
				AutomationState.NAVIGATING_IN_NETHER,
				String.format("Baritone -> Nether (%d, %d)", this.customerTarget.netherX(), this.customerTarget.netherZ())
			);
			return;
		}

		// If the user configured /home 1..4 in the Overworld, proceed directly to Overworld Baritone navigation
		if (config.navigationMode == NavigationMode.MULTI_HOME_MODE
			&& chatConfirmed
			&& isInOverworld(mc)
			&& this.stateTicks >= 20
			&& this.stablePosTicks >= 8) {
			this.commandRetryCount = 0;
			DeliveryBotMod.LOGGER.info(
				"[Step 5] Arrived at Overworld Multi-Home /{} at ({}, {}, {}). Starting Baritone navigation to customer.",
				commandToRun, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ()
			);
			transitionTo(
				mc,
				AutomationState.NAVIGATING_TO_CUSTOMER,
				"Baritone navigating from /" + commandToRun + " to " + this.customerTarget.customerName()
			);
			return;
		}

		if (this.stateTicks >= 120) {
			if (this.commandRetryCount < 3) {
				this.commandRetryCount++;
				this.stateTicks = 0;
				this.statusDetail = "Retrying /" + commandToRun + " (" + this.commandRetryCount + "/3)";
			} else {
				failWithError(mc, "Timed out waiting for /" + commandToRun + " teleport");
			}
		}
	}

	private void tickNavigatingInNether(Minecraft mc, DeliveryConfig config) {
		if (this.customerTarget == null) {
			failWithError(mc, "Missing customer coordinates during Nether navigation");
			return;
		}

		boolean reachedNetherTarget = BaritoneNavigator.navigateToNetherTargetTick(
			mc,
			this.customerTarget,
			config
		);

		double netherDist = this.customerTarget.horizontalDistanceInNetherTo(mc.player.getX(), mc.player.getZ());
		this.statusDetail = String.format(
			"Nether -> (%d, %d) | %.1fm left (%s)",
			this.customerTarget.netherX(),
			this.customerTarget.netherZ(),
			netherDist,
			BaritoneNavigator.getBaritoneStatusSummary()
		);

		if (reachedNetherTarget) {
			BaritoneNavigator.stopBaritone(mc);
			this.portalBuilder.reset();
			this.portalBuilder.initializePlan(mc.player.blockPosition());
			DeliveryBotMod.LOGGER.info(
				"[Step 6] Reached Nether destination ({}, {}); starting Nether Portal construction",
				this.customerTarget.netherX(), this.customerTarget.netherZ()
			);
			transitionTo(mc, AutomationState.BUILDING_NETHER_PORTAL, "Building 4x5 Obsidian Nether Portal");
		}
	}

	// =========================================================================
	// STEP 6: Build a Nether Portal at (X/8, Z/8), light it, and return to Overworld
	// =========================================================================

	private void tickBuildingNetherPortal(Minecraft mc) {
		if (isInOverworld(mc)) {
			onArrivedInOverworldFromPortal(mc);
			return;
		}

		NetherPortalBuilder.BuildResult result = this.portalBuilder.tickBuildAndLight(mc, this.currentOrder);
		int placedCount = this.portalBuilder.countPlacedObsidianInPlan(mc);
		this.statusDetail = String.format("Building Nether Portal (%d/14 Obsidian)", placedCount);

		switch (result) {
			case PORTAL_LIT_READY -> {
				BaritoneNavigator.stopBaritone(mc);
				this.overworldArrivalTicks = 0;
				DeliveryBotMod.LOGGER.info("[Step 6] Nether Portal lit! Stepping into portal to return to Overworld");
				transitionTo(mc, AutomationState.ENTERING_NETHER_PORTAL, "Stepping into Nether Portal -> Overworld");
			}
			case MISSING_OBSIDIAN -> pauseAutomation(
				mc,
				"Missing Obsidian in inventory to build Nether Portal (need 10-14 Obsidian)"
			);
			case MISSING_IGNITER -> pauseAutomation(
				mc,
				"Missing Flint and Steel (or Fire Charge) in inventory to ignite Nether Portal"
			);
			case IN_PROGRESS -> {
				if (this.stateTicks >= 360) {
					failWithError(mc, "Timed out building or igniting Nether Portal");
				}
			}
		}
	}

	private void tickEnteringNetherPortal(Minecraft mc) {
		if (isInOverworld(mc)) {
			BaritoneNavigator.stopBaritone(mc);
			this.overworldArrivalTicks++;
			this.statusDetail = "Arrived in Overworld! Stabilizing chunks...";
			if (this.overworldArrivalTicks >= 10) {
				onArrivedInOverworldFromPortal(mc);
			}
			return;
		}

		boolean insidePortal = this.portalBuilder.enterPortalTick(mc);
		this.statusDetail = insidePortal
			? "Inside Nether Portal (teleporting to Overworld...)"
			: "Walking into lit Nether Portal";

		if (this.stateTicks >= 240) {
			if (NetherPortalBuilder.findNearbyLitPortalBlock(mc, 8) == null) {
				transitionTo(mc, AutomationState.BUILDING_NETHER_PORTAL, "Re-lighting Nether Portal");
			} else if (this.stateTicks >= 360) {
				failWithError(mc, "Timed out waiting for Nether Portal to teleport to Overworld");
			}
		}
	}

	private void onArrivedInOverworldFromPortal(Minecraft mc) {
		if (this.customerTarget == null) {
			failWithError(mc, "Missing customer coordinates upon arriving in Overworld");
			return;
		}

		double horizDist = this.customerTarget.horizontalDistanceTo(mc.player.getX(), mc.player.getZ());
		DeliveryBotMod.LOGGER.info(
			"[Step 7] Back in Overworld at ({}, {}, {}). Starting Baritone automatic navigation to {} (dist={:.1f}m)",
			(int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ(),
			this.customerTarget.customerName(), horizDist
		);
		transitionTo(
			mc,
			AutomationState.NAVIGATING_TO_CUSTOMER,
			"Baritone navigating to " + this.customerTarget.customerName()
		);
	}

	// =========================================================================
	// STEP 7: Use Baritone API in the Overworld to automatically navigate to the
	//         customer's exact coordinates and break any obstructing blocks
	// =========================================================================

	private void tickNavigatingToCustomer(Minecraft mc, DeliveryConfig config) {
		if (this.customerTarget == null) {
			failWithError(mc, "Missing customer target during Baritone navigation");
			return;
		}

		Entity detectedCustomer = NpcDetector.findCustomerNpc(mc, this.customerTarget);

		// If a block directly in front of the customer obstructs interaction at close range, break it with the best tool
		if (detectedCustomer != null && mc.player.distanceTo(detectedCustomer) <= 5.5) {
			BlockPos obstructingPos = this.blockBreaker.findObstructingBlockDuringDescent(
				mc,
				this.customerTarget,
				detectedCustomer
			);
			if (obstructingPos != null) {
				BaritoneNavigator.stopBaritone(mc);
				this.blockBreaker.breakBlockTick(mc, obstructingPos, this.currentOrder);
				this.statusDetail = String.format(
					"Breaking obstructing block (%d, %d, %d)",
					obstructingPos.getX(), obstructingPos.getY(), obstructingPos.getZ()
				);
				return;
			}
			this.blockBreaker.stopBreaking(mc);
		}

		boolean arrived = BaritoneNavigator.navigateToCustomerInOverworldTick(
			mc,
			this.customerTarget,
			detectedCustomer,
			config
		);

		double horizDist = this.customerTarget.horizontalDistanceTo(mc.player.getX(), mc.player.getZ());
		this.statusDetail = String.format(
			"To %s (%.1fm) | %s",
			this.customerTarget.customerName(),
			horizDist,
			BaritoneNavigator.getBaritoneStatusSummary()
		);

		if (arrived) {
			BaritoneNavigator.stopBaritone(mc);
			transitionTo(mc, AutomationState.LOCATING_CUSTOMER, "Arrived at destination; approaching customer NPC");
		}
	}

	// =========================================================================
	// STEP 8: Locate customer NPC, equip package, and complete delivery
	// =========================================================================

	private void tickLocatingCustomer(Minecraft mc, DeliveryConfig config) {
		if (this.customerTarget == null) {
			failWithError(mc, "Missing customer target");
			return;
		}

		Entity customerNpc = NpcDetector.findCustomerNpc(mc, this.customerTarget);

		BlockPos obstructingPos = this.blockBreaker.findObstructingBlockDuringDescent(
			mc,
			this.customerTarget,
			customerNpc
		);
		if (obstructingPos != null) {
			BaritoneNavigator.stopBaritone(mc);
			this.blockBreaker.breakBlockTick(mc, obstructingPos, this.currentOrder);
			this.statusDetail = String.format(
				"Clearing block (%d, %d, %d) to reach customer",
				obstructingPos.getX(), obstructingPos.getY(), obstructingPos.getZ()
			);
			return;
		}
		this.blockBreaker.stopBreaking(mc);

		if (customerNpc == null) {
			double horizDist = this.customerTarget.horizontalDistanceTo(mc.player.getX(), mc.player.getZ());
			if (horizDist > 2.5) {
				transitionTo(mc, AutomationState.NAVIGATING_TO_CUSTOMER, "Continuing Baritone path to customer coordinates");
				return;
			}
			Vec3 targetCenter = this.customerTarget.centerPos();
			FlightNavigator.rotateTowards(mc.player, targetCenter, 25.0f, 18.0f);
			this.statusDetail = "Searching for customer NPC: " + this.customerTarget.customerName();
			if (this.stateTicks >= 160) {
				failWithError(mc, "Could not find customer NPC '" + this.customerTarget.customerName() + "' at destination");
			}
			return;
		}

		boolean inRangeAndAimed = BaritoneNavigator.navigateToNpcTick(
			mc,
			customerNpc,
			config.customerInteractionDistance,
			config
		);
		if (!inRangeAndAimed) {
			double dist = mc.player.getEyePosition().distanceTo(customerNpc.getBoundingBox().getCenter());
			this.statusDetail = String.format("Approaching %s (%.1fm)", NpcDetector.extractEntityName(customerNpc), dist);
			return;
		}

		BaritoneNavigator.stopBaritone(mc);
		this.equipWaitTicks = 0;
		transitionTo(mc, AutomationState.DELIVERING_PACKAGE, "Equipping package & delivering to " + this.customerTarget.customerName());
	}

	private void tickDeliveringPackage(Minecraft mc, DeliveryConfig config) {
		BaritoneNavigator.stopBaritone(mc);
		this.blockBreaker.stopBreaking(mc);

		if (!GuiInventoryHelper.hasDeliveryPackage(mc, this.currentOrder)) {
			if (ChatMonitor.getInstance().isDeliverySuccessConfirmed()) {
				onDeliveryConfirmed(mc);
				return;
			}
			failWithError(mc, "Delivery package is missing from player inventory");
			return;
		}

		boolean equipped = GuiInventoryHelper.ensurePackageInMainHand(mc, this.currentOrder);
		if (!equipped) {
			failWithError(mc, "Failed to equip delivery package into main hand");
			return;
		}

		Entity customerNpc = NpcDetector.findCustomerNpc(mc, this.customerTarget);
		if (customerNpc == null) {
			transitionTo(mc, AutomationState.LOCATING_CUSTOMER, "Re-locating customer NPC");
			return;
		}

		Vec3 npcCenter = customerNpc.getBoundingBox().getCenter();
		double dist = mc.player.getEyePosition().distanceTo(npcCenter);
		if (dist > config.customerInteractionDistance + 0.5) {
			transitionTo(mc, AutomationState.LOCATING_CUSTOMER, "Moving closer to customer NPC");
			return;
		}

		FlightNavigator.rotateTowards(mc.player, npcCenter, 30.0f, 20.0f);

		this.equipWaitTicks++;
		if (this.equipWaitTicks < 4) {
			return;
		}

		ChatMonitor.getInstance().resetDeliveryAttemptFlags();
		this.deliveryRetryCount++;
		DeliveryBotMod.LOGGER.info(
			"[Step 8] Right-clicking customer '{}' with package (attempt {}/{})",
			NpcDetector.extractEntityName(customerNpc), this.deliveryRetryCount, config.maxDeliveryRetries
		);
		NpcDetector.interactWithNpc(mc, customerNpc);
		transitionTo(mc, AutomationState.VERIFYING_DELIVERY, "Waiting for GIAO HÀNG THÀNH CÔNG confirmation");
	}

	private void tickVerifyingDelivery(Minecraft mc, DeliveryConfig config) {
		BaritoneNavigator.stopBaritone(mc);
		ChatMonitor chat = ChatMonitor.getInstance();

		if (chat.isDeliverySuccessConfirmed()) {
			onDeliveryConfirmed(mc);
			return;
		}

		if (chat.isWrongHandItemWarning() || this.stateTicks >= 35) {
			if (this.deliveryRetryCount < config.maxDeliveryRetries) {
				DeliveryBotMod.LOGGER.info(
					"[Step 8] Delivery not yet confirmed; retrying ({}/{})",
					this.deliveryRetryCount, config.maxDeliveryRetries
				);
				transitionTo(mc, AutomationState.LOCATING_CUSTOMER, "Retrying delivery interaction (" + this.deliveryRetryCount + "/" + config.maxDeliveryRetries + ")");
			} else {
				failWithError(mc, "Exceeded maximum delivery interaction retries (" + config.maxDeliveryRetries + ")");
			}
		}
	}

	private void onDeliveryConfirmed(Minecraft mc) {
		this.completedDeliveries++;
		long fallbackReward = this.currentOrder != null ? this.currentOrder.baseRewardXu() : 0L;
		long earnedThisDelivery = ChatMonitor.getInstance().resolveCompletedOrderEarnings(fallbackReward);
		this.totalEarnedXu += earnedThisDelivery;

		String customerName = this.customerTarget != null ? this.customerTarget.customerName() : "Customer";
		DeliveryBotMod.LOGGER.info(
			"[Step 8] GIAO HÀNG THÀNH CÔNG! Completed delivery #{} to {} (+{} xu | Total: {} xu)",
			this.completedDeliveries, customerName, earnedThisDelivery, this.totalEarnedXu
		);
		TextUtils.sendClientMessage(
			mc,
			String.format(
				"GIAO HÀNG THÀNH CÔNG! #%d (+%,d xu | Total: %,d xu)",
				this.completedDeliveries, earnedThisDelivery, this.totalEarnedXu
			),
			ChatFormatting.GREEN
		);
		transitionTo(mc, AutomationState.DELIVERY_COMPLETED, "Delivery #" + this.completedDeliveries + " completed!");
	}

	private void tickDeliveryCompleted(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		if (this.stateTicks >= 25) {
			transitionTo(mc, AutomationState.STARTING_NEXT_ORDER, "Preparing next delivery cycle");
		}
	}

	private void tickStartingNextOrder(Minecraft mc) {
		BaritoneNavigator.stopBaritone(mc);
		if (this.stateTicks >= 10) {
			resetCurrentOrderData(mc);
			transitionTo(mc, AutomationState.WARPING_TO_PICKUP, "Starting next job -> /home lambanh");
		}
	}

	private static boolean isInNether(Minecraft mc) {
		if (mc == null || mc.level == null) {
			return false;
		}
		return mc.level.dimension() == Level.NETHER
			|| mc.level.dimension().identifier().getPath().contains("nether");
	}

	private static boolean isInOverworld(Minecraft mc) {
		if (mc == null || mc.level == null) {
			return false;
		}
		return mc.level.dimension() == Level.OVERWORLD
			|| mc.level.dimension().identifier().getPath().equals("overworld");
	}

	// =========================================================================
	// Status & Earnings Getters for HUD & Control Screen
	// =========================================================================

	public AutomationState getState() {
		return state;
	}

	public String getStatusDetail() {
		return statusDetail;
	}

	public DeliveryOrder getCurrentOrder() {
		return currentOrder;
	}

	public CustomerTarget getCustomerTarget() {
		return customerTarget;
	}

	public int getCompletedDeliveries() {
		return completedDeliveries;
	}

	public long getTotalEarnedXu() {
		return totalEarnedXu;
	}

	public long getTotalActiveRunTimeMillis() {
		long currentDelta = (this.activeSessionStartMillis > 0L)
			? Math.max(0L, System.currentTimeMillis() - this.activeSessionStartMillis)
			: 0L;
		return this.accumulatedRunMillis + currentDelta;
	}

	public String getFormattedRunTime() {
		long totalSeconds = getTotalActiveRunTimeMillis() / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

	public long getAverageXuPerMinute() {
		double minutes = getTotalActiveRunTimeMillis() / 60000.0;
		if (minutes < 0.05 || this.totalEarnedXu <= 0L) {
			return 0L;
		}
		return Math.round(this.totalEarnedXu / minutes);
	}

	public int getSelectedHomeIndex() {
		return selectedHomeIndex;
	}

	public String getLastErrorMessage() {
		return lastErrorMessage;
	}

	public double getCurrentDistanceToCustomer(Minecraft mc) {
		if (mc == null || mc.player == null || this.customerTarget == null) {
			return -1.0;
		}
		if (isInNether(mc)) {
			return this.customerTarget.horizontalDistanceInNetherTo(mc.player.getX(), mc.player.getZ());
		}
		return this.customerTarget.horizontalDistanceTo(mc.player.getX(), mc.player.getZ());
	}
}
