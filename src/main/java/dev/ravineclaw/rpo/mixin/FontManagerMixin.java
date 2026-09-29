package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ListenerReuse;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontManager.class)
public abstract class FontManagerMixin {
	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable InputRecording rpo$pending;
	@Unique
	private volatile boolean rpo$runVanilla;

	@Inject(method = "reload", at = @At("HEAD"), cancellable = true)
	private void rpo$keepUnchanged(
		final PreparableReloadListener.PreparationBarrier preparationBarrier,
		final ResourceManager manager,
		final Executor taskExecutor,
		final Executor reloadExecutor,
		final CallbackInfoReturnable<CompletableFuture<Void>> cir
	) {
		if (this.rpo$runVanilla) {
			this.rpo$runVanilla = false;
			return;
		}

		if (!RpoSettings.active() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("fonts", ReuseGuard.FONTS)) {
			this.rpo$applied = null;
			this.rpo$pending = null;
			return;
		}

		FontManager self = (FontManager)(Object)this;
		cir.setReturnValue(ListenerReuse.canKeep(this.rpo$applied, manager, List.of(), taskExecutor).thenCompose(keep -> {
			if (keep) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> {
				}, reloadExecutor);
			}

			this.rpo$pending = InputRecording.start(manager);
			this.rpo$runVanilla = true;
			return self.reload(preparationBarrier, manager, taskExecutor, reloadExecutor);
		}));
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/font/FontManager;prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<?> rpo$recordReads(
		final FontManager self, final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<?>> original
	) {
		InputRecording pending = this.rpo$pending;
		return original.call(self, pending != null ? pending.manager() : manager, executor);
	}

	@Inject(method = "apply", at = @At("HEAD"))
	private void rpo$forgetApplied(final CallbackInfo ci) {
		this.rpo$applied = null;
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void rpo$rememberApplied(final CallbackInfo ci) {
		this.rpo$applied = this.rpo$pending;
		this.rpo$pending = null;
	}
}
