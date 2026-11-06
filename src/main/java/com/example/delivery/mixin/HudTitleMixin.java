package com.example.delivery.mixin;

import com.example.delivery.chat.ChatMonitor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link Hud} title, subtitle, and overlay setters so the delivery bot immediately detects:
 * <ul>
 *   <li>{@code Teleporting in 5 seconds...} and {@code Teleported!} during {@code /rtp}</li>
 *   <li>{@code GIAO HÀNG THÀNH CÔNG} upon delivering the package</li>
 *   <li>Actionbar world & distance indicators</li>
 * </ul>
 */
@Mixin(Hud.class)
public abstract class HudTitleMixin {
	@Inject(method = "setTitle", at = @At("TAIL"))
	private void deliverybot$onSetTitle(Component title, CallbackInfo ci) {
		ChatMonitor.getInstance().onTitleSet(title);
	}

	@Inject(method = "setSubtitle", at = @At("TAIL"))
	private void deliverybot$onSetSubtitle(Component subtitle, CallbackInfo ci) {
		ChatMonitor.getInstance().onSubtitleSet(subtitle);
	}

	@Inject(method = "setOverlayMessage", at = @At("TAIL"))
	private void deliverybot$onSetOverlayMessage(Component message, boolean animate, CallbackInfo ci) {
		ChatMonitor.getInstance().onOverlayMessage(message);
	}
}