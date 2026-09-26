package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.ReloadTimeline;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
	@Unique
	private @Nullable PreparableReloadListener rpo$lastListener;

	@Inject(method = "prepareTasks", at = @At("HEAD"))
	private void rpo$startTimeline(final CallbackInfoReturnable<CompletableFuture<?>> cir) {
		if (ReloadTimeline.ENABLED) {
			ReloadTimeline.begin();
		}
	}

	@WrapOperation(
		method = "prepareTasks",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;createBarrierForListener(Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
		)
	)
	private PreparableReloadListener.PreparationBarrier rpo$timePreparation(
		final SimpleReloadInstance<?> self,
		final PreparableReloadListener listener,
		final CompletableFuture<?> previousBarrier,
		final Executor mainThreadExecutor,
		final Operation<PreparableReloadListener.PreparationBarrier> original
	) {
		PreparableReloadListener.PreparationBarrier barrier = original.call(self, listener, previousBarrier, mainThreadExecutor);
		this.rpo$lastListener = listener;
		if (!ReloadTimeline.ENABLED) {
			return barrier;
		}

		return ReloadTimeline.timed(listener, barrier);
	}

	@WrapOperation(method = "prepareTasks", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean rpo$timeApply(final List<Object> steps, final Object step, final Operation<Boolean> original) {
		PreparableReloadListener listener = this.rpo$lastListener;
		this.rpo$lastListener = null;
		if (ReloadTimeline.ENABLED && listener != null && step instanceof CompletableFuture<?> future) {
			ReloadTimeline.track(listener, future);
		}

		return original.call(steps, step);
	}
}
