package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ListenerReuse;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ReuseGuard;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {
	@Shadow
	@Final
	private AtlasSet atlases;
	@Shadow
	private int maxMipmapLevels;

	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable InputRecording rpo$pending;
	@Unique
	private volatile @Nullable Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> rpo$scheduledAtlases;
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

		this.rpo$scheduledAtlases = null;
		if (!InputRecording.isTrackable(manager) || !ReuseGuard.untouched("models", ReuseGuard.MODELS)) {
			this.rpo$applied = null;
			this.rpo$pending = null;
			return;
		}

		ModelManager self = (ModelManager)(Object)this;
		Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> atlasFutures = this.atlases.scheduleLoad(manager, this.maxMipmapLevels, taskExecutor);
		cir.setReturnValue(ListenerReuse.canKeep(this.rpo$applied, manager, atlasFutures.values(), taskExecutor).thenCompose(keep -> {
			if (keep) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> {
					ReloadChanges.unchanged(ReloadChanges.MODELS);
					for (ResourceLocation atlas : atlasFutures.keySet()) {
						ReloadChanges.unchanged("atlas:" + atlas);
					}
				}, reloadExecutor);
			}

			this.rpo$pending = InputRecording.start(manager);
			this.rpo$scheduledAtlases = atlasFutures;
			this.rpo$runVanilla = true;
			return self.reload(preparationBarrier, manager, taskExecutor, reloadExecutor);
		}));
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/AtlasSet;scheduleLoad(Lnet/minecraft/server/packs/resources/ResourceManager;ILjava/util/concurrent/Executor;)Ljava/util/Map;"
		)
	)
	private Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> rpo$useScheduledAtlases(
		final AtlasSet atlasSet,
		final ResourceManager manager,
		final int mipLevels,
		final Executor executor,
		final Operation<Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>>> original
	) {
		Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> scheduled = this.rpo$scheduledAtlases;
		this.rpo$scheduledAtlases = null;
		return scheduled != null ? scheduled : original.call(atlasSet, manager, mipLevels, executor);
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/ModelManager;loadBlockModels(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<?> rpo$recordBlockModels(final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<?>> original) {
		return original.call(this.rpo$recording(manager), executor);
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadBlockStates(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<?> rpo$recordBlockStates(final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<?>> original) {
		return original.call(this.rpo$recording(manager), executor);
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/ClientItemInfoLoader;scheduleLoad(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<?> rpo$recordItems(final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<?>> original) {
		return original.call(this.rpo$recording(manager), executor);
	}

	@Unique
	private ResourceManager rpo$recording(final ResourceManager manager) {
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
