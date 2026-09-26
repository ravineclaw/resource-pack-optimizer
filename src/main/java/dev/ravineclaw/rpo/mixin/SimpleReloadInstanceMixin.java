package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.ReloadTimeline;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
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
		if (!ReloadTimeline.ENABLED) {
			return barrier;
		}

		return ReloadTimeline.timed(listener, barrier);
	}

	@WrapOperation(method = "prepareTasks", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean rpo$timeApply(
		final List<Object> steps, final Object step, final Operation<Boolean> original, @Local final PreparableReloadListener listener
	) {
		if (ReloadTimeline.ENABLED && step instanceof CompletableFuture<?> future) {
			ReloadTimeline.track(listener, future);
		}

		return original.call(steps, step);
	}
}
