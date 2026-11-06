package com.example.delivery;

import com.example.delivery.automation.DeliveryAutomationController;
import com.example.delivery.chat.ChatMonitor;
import com.example.delivery.config.DeliveryConfig;
import com.example.delivery.gui.DeliveryControlScreen;
import com.example.delivery.gui.DeliveryHudOverlay;
import com.example.delivery.navigation.DeliveryLocationStorage;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for the Minecraft 26.2 Fabric Automated Delivery Bot.
 *
 * <p>Default Keybindings:</p>
 * <ul>
 *   <li>{@code R}: Start / Stop Delivery Automation</li>
 *   <li>{@code P}: Pause / Resume Delivery Automation</li>
 *   <li>{@code O}: Open Delivery Control &amp; Settings GUI</li>
 * </ul>
 */
public final class DeliveryBotMod implements ClientModInitializer {
	public static final String MOD_ID = "deliverybot";
	public static final Logger LOGGER = LoggerFactory.getLogger("DeliveryBot");

	private static KeyMapping toggleStartStopKey;
	private static KeyMapping pauseResumeKey;
	private static KeyMapping openControlGuiKey;

	@Override
	public void onInitializeClient() {
		DeliveryConfig.get();
		DeliveryLocationStorage.getInstance();

		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(MOD_ID, "general")
		);

		toggleStartStopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.deliverybot.toggle",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			category
		));

		pauseResumeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.deliverybot.pause",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_P,
			category
		));

		openControlGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.deliverybot.config",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_O,
			category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleStartStopKey.consumeClick()) {
				DeliveryAutomationController.getInstance().toggleStartStop(client);
			}
			while (pauseResumeKey.consumeClick()) {
				DeliveryAutomationController.getInstance().togglePauseResume(client);
			}
			while (openControlGuiKey.consumeClick()) {
				if (client.gui != null && client.gui.screen() == null) {
					client.gui.setScreen(new DeliveryControlScreen(null));
				}
			}
			DeliveryAutomationController.getInstance().onClientTick(client);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			DeliveryAutomationController.getInstance().onJoinedServer(client)
		);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			DeliveryAutomationController.getInstance().onDisconnectedFromServer(client)
		);

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				ChatMonitor.getInstance().onOverlayMessage(message);
			} else {
				ChatMonitor.getInstance().onChatMessage(message);
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
			ChatMonitor.getInstance().onChatMessage(message)
		);

		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath(MOD_ID, "status_hud"),
			DeliveryHudOverlay::render
		);

		LOGGER.info("Automated Delivery Bot initialized for Minecraft 26.2 (R=Start/Stop, P=Pause/Resume, O=Control GUI)");
	}
}