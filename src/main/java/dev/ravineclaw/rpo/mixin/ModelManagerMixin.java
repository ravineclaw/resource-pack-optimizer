package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ListenerReuse;
import dev.ravineclaw.rpo.ParsedModels;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.jetbrains.annotations.Nullable;
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
	@Unique
	private volatile @Nullable ParsedModels rpo$appliedParsed;
	@Unique
	private volatile @Nullable ParsedModels rpo$reuseParsed;
	@Unique
	private volatile @Nullable CompletableFuture<Map<ResourceLocation, UnbakedModel>> rpo$pendingModels;
	@Unique
	private volatile @Nullable CompletableFuture<BlockStateModelLoader.LoadedModels> rpo$pendingBlockStates;
	@Unique
	private volatile @Nullable CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> rpo$pendingItems;

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
		if (!RpoSettings.active() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("models", ReuseGuard.MODELS)) {
			this.rpo$applied = null;
			this.rpo$pending = null;
			this.rpo$appliedParsed = null;
			this.rpo$reuseParsed = null;
			return;
		}

		ModelManager self = (ModelManager)(Object)this;
		Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> atlasFutures = this.atlases.scheduleLoad(manager, this.maxMipmapLevels, taskExecutor);
		InputRecording applied = this.rpo$applied;
		ParsedModels parsed = this.rpo$appliedParsed;
		cir.setReturnValue(ListenerReuse.inputsMatch(applied, manager, taskExecutor).thenCompose(same -> {
			if (!same) {
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}

			return ListenerReuse.allAtlasesKept(atlasFutures.values()).thenApply(kept -> kept ? Boolean.TRUE : parsed != null ? null : Boolean.FALSE);
		}).thenCompose(keep -> {
			if (keep == Boolean.TRUE) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> {
					for (CompletableFuture<AtlasSet.StitchResult> atlas : atlasFutures.values()) {
						atlas.join().upload();
					}

					ReloadChanges.unchanged(ReloadChanges.MODELS);
				}, reloadExecutor);
			}

			if (keep == null) {
				this.rpo$pending = applied;
				this.rpo$reuseParsed = parsed;
			} else {
				this.rpo$pending = InputRecording.start(manager);
				this.rpo$reuseParsed = null;
			}

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
	private Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> rpo$reuseScheduledAtlases(
		final AtlasSet set,
		final ResourceManager manager,
		final int mipLevels,
		final Executor executor,
		final Operation<Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>>> original
	) {
		Map<ResourceLocation, CompletableFuture<AtlasSet.StitchResult>> scheduled = this.rpo$scheduledAtlases;
		this.rpo$scheduledAtlases = null;
		return scheduled != null ? scheduled : original.call(set, manager, mipLevels, executor);
	}

	@Unique
	private ResourceManager rpo$recording(final ResourceManager manager) {
		InputRecording pending = this.rpo$pending;
		return pending != null && this.rpo$reuseParsed == null ? pending.manager() : manager;
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/ModelManager;loadBlockModels(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Map<ResourceLocation, UnbakedModel>> rpo$keepParsedModels(
		final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<Map<ResourceLocation, UnbakedModel>>> original
	) {
		ParsedModels parsed = this.rpo$reuseParsed;
		CompletableFuture<Map<ResourceLocation, UnbakedModel>> result = parsed != null ? CompletableFuture.completedFuture(parsed.models()) : original.call(this.rpo$recording(manager), executor);
		this.rpo$pendingModels = result;
		return result;
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/BlockStateModelLoader;loadBlockStates(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<BlockStateModelLoader.LoadedModels> rpo$keepParsedBlockStates(
		final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<BlockStateModelLoader.LoadedModels>> original
	) {
		ParsedModels parsed = this.rpo$reuseParsed;
		CompletableFuture<BlockStateModelLoader.LoadedModels> result = parsed != null ? CompletableFuture.completedFuture(parsed.blockStates()) : original.call(this.rpo$recording(manager), executor);
		this.rpo$pendingBlockStates = result;
		return result;
	}

	@WrapOperation(
		method = "reload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/ClientItemInfoLoader;scheduleLoad(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> rpo$keepParsedItems(
		final ResourceManager manager, final Executor executor, final Operation<CompletableFuture<ClientItemInfoLoader.LoadedClientInfos>> original
	) {
		ParsedModels parsed = this.rpo$reuseParsed;
		this.rpo$reuseParsed = null;
		CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> result = parsed != null ? CompletableFuture.completedFuture(parsed.items()) : original.call(this.rpo$recording(manager), executor);
		this.rpo$pendingItems = result;
		return result;
	}

	@Inject(method = "apply", at = @At("HEAD"))
	private void rpo$forgetApplied(final CallbackInfo ci) {
		this.rpo$applied = null;
		this.rpo$appliedParsed = null;
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void rpo$rememberApplied(final CallbackInfo ci) {
		this.rpo$applied = this.rpo$pending;
		this.rpo$appliedParsed = this.rpo$pending != null ? ParsedModels.of(this.rpo$pendingModels, this.rpo$pendingBlockStates, this.rpo$pendingItems) : null;
		this.rpo$pending = null;
		this.rpo$pendingModels = null;
		this.rpo$pendingBlockStates = null;
		this.rpo$pendingItems = null;
	}
}
