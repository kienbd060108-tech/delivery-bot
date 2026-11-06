package com.example.delivery.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu integration allowing the user to open {@link DeliveryControlScreen}
 * directly from the Mods list.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return DeliveryControlScreen::new;
	}
}