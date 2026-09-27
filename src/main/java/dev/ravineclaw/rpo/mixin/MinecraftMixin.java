package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.BackgroundReload;
import dev.ravineclaw.rpo.FramePump;
import dev.ravineclaw.rpo.ReloadTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@WrapOperation(
		method = "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V")
	)
	private void rpo$reloadInBackground(final Minecraft minecraft, final Overlay overlay, final Operation<Void> original, @Local(argsOnly = true) final boolean isRecovery) {
		BackgroundReload.clear();
		if (!isRecovery && overlay instanceof LoadingOverlay loading) {
			BackgroundReload.start(loading);
		} else {
			original.call(minecraft, overlay);
		}
	}

	@WrapOperation(
		method = {"reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", "runTick"},
		constant = @Constant(classValue = LoadingOverlay.class)
	)
	private boolean rpo$backgroundReloadCounts(final Object overlay, final Operation<Boolean> original) {
		return original.call(overlay) || BackgroundReload.current() != null;
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
