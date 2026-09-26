package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ListenerReuse;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ReuseGuard;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.ModelManager;
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

@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {
	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable InputRecording rpo$pending;
	@Unique
	private volatile boolean rpo$runVanilla;

	@Inject(method = "reload", at = @At("HEAD"), cancellable = true)
	private void rpo$keepUnchanged(
		final PreparableReloadListener.SharedState currentReload,
		final Executor taskExecutor,
		final PreparableReloadListener.PreparationBarrier preparationBarrier,
		final Executor reloadExecutor,
		final CallbackInfoReturnable<CompletableFuture<Void>> cir
	) {
		if (this.rpo$runVanilla) {
			this.rpo$runVanilla = false;
			return;
		}

		ResourceManager manager = currentReload.resourceManager();
		if (!InputRecording.isTrackable(manager) || !ReuseGuard.untouched("models", ReuseGuard.MODELS)) {
			this.rpo$applied = null;
			this.rpo$pending = null;
			return;
		}

		ModelManager self = (ModelManager)(Object)this;
		cir.setReturnValue(ListenerReuse.canKeep(this.rpo$applied, currentReload, taskExecutor).thenCompose(keep -> {
			if (keep) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> ReloadChanges.unchanged(ReloadChanges.MODELS), reloadExecutor);
			}

			this.rpo$pending = InputRecording.start(manager);
			this.rpo$runVanilla = true;
			return self.reload(currentReload, taskExecutor, preparationBarrier, reloadExecutor);
		}));
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;resourceManager()Lnet/minecraft/server/packs/resources/ResourceManager;"
		)
	)
	private ResourceManager rpo$recordReads(final PreparableReloadListener.SharedState currentReload, final Operation<ResourceManager> original) {
		ResourceManager manager = original.call(currentReload);
		InputRecording pending = this.rpo$pending;
		return pending != null ? pending.manager() : manager;
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
