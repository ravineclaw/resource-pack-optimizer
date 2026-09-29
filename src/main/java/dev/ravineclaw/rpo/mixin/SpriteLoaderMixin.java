package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.DeferredMipmaps;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.SpriteCache;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderMixin {
	@Unique
	private static final ThreadLocal<Boolean> RPO_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Shadow
	@Final
	private ResourceLocation location;
	@Shadow
	@Final
	private int maxSupportedTextureSize;

	@Inject(
		method = "loadAndStitch(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceLocation;ILjava/util/concurrent/Executor;Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void rpo$reuseUnchanged(
		final ResourceManager manager,
		final ResourceLocation atlasInfoLocation,
		final int maxMipmapLevels,
		final Executor taskExecutor,
		final Collection<MetadataSectionType<?>> additionalMetadata,
		final CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir
	) {
		if (RPO_BYPASS.get() || !RpoSettings.active() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("atlases", ReuseGuard.ATLASES)) {
			return;
		}

		SpriteLoader self = (SpriteLoader)(Object)this;
		ResourceLocation atlas = this.location;
		AtlasReuse.Key key = new AtlasReuse.Key(atlasInfoLocation, maxMipmapLevels, this.maxSupportedTextureSize, Set.copyOf(additionalMetadata));
		AtlasReuse.Entry current = AtlasReuse.uploaded(atlas);
		CompletableFuture<Boolean> unchanged = current != null && current.key().equals(key)
			? CompletableFuture.supplyAsync(() -> current.recording().matches(manager), taskExecutor)
			: CompletableFuture.completedFuture(Boolean.FALSE);
		cir.setReturnValue(unchanged.thenCompose(same -> {
			if (same) {
				return CompletableFuture.completedFuture(current.preparations());
			}

			InputRecording recording = InputRecording.start(manager);
			CompletableFuture<SpriteLoader.Preparations> result;
			RPO_BYPASS.set(Boolean.TRUE);
			try {
				result = self.loadAndStitch(recording.manager(), atlasInfoLocation, maxMipmapLevels, taskExecutor, additionalMetadata);
			} finally {
				RPO_BYPASS.set(Boolean.FALSE);
			}

			return result.thenApply(preparations -> {
				AtlasReuse.built(atlas, new AtlasReuse.Entry(key, recording, preparations));
				return preparations;
			});
		}));
	}

	@WrapOperation(
		method = "loadAndStitch(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceLocation;ILjava/util/concurrent/Executor;Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;create(Ljava/util/Collection;)Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;"
		)
	)
	private SpriteResourceLoader rpo$cachedSprites(final Collection<MetadataSectionType<?>> additionalMetadata, final Operation<SpriteResourceLoader> original) {
		return SpriteCache.wrap(original.call(additionalMetadata));
	}

	@WrapOperation(
		method = "stitch",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Void> rpo$deferMipmaps(final Runnable task, final Executor executor, final Operation<CompletableFuture<Void>> original) {
		if (!RpoSettings.active()) {
			return original.call(task, executor);
		}

		return new DeferredMipmaps(() -> original.call(task, executor), executor);
	}

	@WrapOperation(
		method = "stitch",
		at = @At(
			value = "NEW",
			target = "(IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;Ljava/util/Map;Ljava/util/concurrent/CompletableFuture;)Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;"
		)
	)
	private SpriteLoader.Preparations rpo$parallelMipmaps(
		final int width,
		final int height,
		final int mipLevel,
		final TextureAtlasSprite missing,
		final Map<ResourceLocation, TextureAtlasSprite> regions,
		final CompletableFuture<Void> readyForUpload,
		final Operation<SpriteLoader.Preparations> original,
		@Local(argsOnly = true) final Executor executor
	) {
		CompletableFuture<Void> ready = readyForUpload instanceof DeferredMipmaps deferred ? deferred.start(regions, mipLevel) : readyForUpload;
		return original.call(width, height, mipLevel, missing, regions, AtlasBuilder.buildAfter(ready, this.location, regions, width, height, mipLevel, executor));
	}
}
