package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.ReloadTimeline;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
	@Inject(method = "create", at = @At("HEAD"))
	private static void rpo$startTimeline(
		final ResourceManager manager,
		final List<PreparableReloadListener> listeners,
		final Executor backgroundExecutor,
		final Executor mainThreadExecutor,
		final CompletableFuture<Unit> initialTask,
		final boolean profiled,
		final CallbackInfoReturnable<ReloadInstance> cir
	) {
		if (ReloadTimeline.ENABLED) {
			ReloadTimeline.begin();
		}
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		),
		index = 0
	)
	private PreparableReloadListener.PreparationBarrier rpo$timePreparation(
		final PreparableReloadListener.PreparationBarrier barrier,
		final ResourceManager manager,
		final PreparableReloadListener listener,
		final Executor backgroundExecutor,
		final Executor mainThreadExecutor
	) {
		return ReloadTimeline.ENABLED ? ReloadTimeline.timed(listener, barrier) : barrier;
	}

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean rpo$timeApply(final List<Object> steps, final Object step, final Operation<Boolean> original, @Local final PreparableReloadListener listener) {
		if (ReloadTimeline.ENABLED && step instanceof CompletableFuture<?> future) {
			ReloadTimeline.track(listener, future);
		}

		return original.call(steps, step);
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		),
		index = 4
	)
	private Executor rpo$timeMainThread(
		final PreparableReloadListener.PreparationBarrier barrier,
		final ResourceManager resourceManager,
		final PreparableReloadListener listener,
		final Executor taskExecutor,
		final Executor reloadExecutor
	) {
		return ReloadTimeline.ENABLED ? ReloadTimeline.timedMainThread(listener, reloadExecutor) : reloadExecutor;
	}
}
