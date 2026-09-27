package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.BackgroundReload;
import dev.ravineclaw.rpo.FramePump;
import dev.ravineclaw.rpo.ReloadTimeline;
import dev.ravineclaw.rpo.RpoSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@WrapOperation(
		method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V")
	)
	private void rpo$reloadInBackground(final Gui gui, final Overlay overlay, final Operation<Void> original, @Local(argsOnly = true) final boolean isRecovery) {
		BackgroundReload.clear();
		if (!isRecovery && RpoSettings.active() && overlay instanceof LoadingOverlay loading) {
			BackgroundReload.start(loading);
		} else {
			original.call(gui, overlay);
		}
	}

	@WrapOperation(
		method = {"reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", "runTick"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;")
	)
	private Overlay rpo$backgroundReloadCounts(final Gui gui, final Operation<Overlay> original) {
		Overlay overlay = original.call(gui);
		return overlay != null ? overlay : BackgroundReload.current();
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void rpo$countFrame(final boolean advanceGameTime, final CallbackInfo ci) {
		if (ReloadTimeline.ENABLED) {
			ReloadTimeline.frame();
		}
	}

	@Inject(method = {"runTick", "doWorldLoad"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
	private void rpo$pumpFrameWork(final CallbackInfo ci) {
		FramePump.pump();
	}
}
