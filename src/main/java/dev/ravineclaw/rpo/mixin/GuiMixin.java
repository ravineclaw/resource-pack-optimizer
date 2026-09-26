package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.BackgroundReload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void rpo$tickBackgroundReload(final CallbackInfo ci) {
		BackgroundReload.tick();
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void rpo$extractBackgroundReload(
		final DeltaTracker deltaTracker, final boolean shouldRenderLevel, final boolean resourcesLoaded, final CallbackInfo ci, @Local final GuiGraphicsExtractor graphics
	) {
		BackgroundReload.extract(graphics, deltaTracker.getGameTimeDeltaTicks());
	}
}
