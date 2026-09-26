package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.BackgroundReload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererReloadMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;render(Lnet/minecraft/client/gui/GuiGraphics;)V", shift = At.Shift.AFTER))
	private void rpo$renderBackgroundReload(final DeltaTracker deltaTracker, final boolean renderLevel, final CallbackInfo ci, @Local final GuiGraphics graphics) {
		BackgroundReload.render(graphics, deltaTracker.getGameTimeDeltaTicks());
	}
}
