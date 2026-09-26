package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.DeferredMipmaps;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReuseGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
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
	@Unique
	private static final ThreadLocal<DeferredMipmaps> RPO_MIPMAPS = new ThreadLocal<>();

	@Shadow
	@Final
	private ResourceLocation location;
	@Shadow
	@Final
	private int maxSupportedTextureSize;
	@Shadow
	@Final
	private int minWidth;
	@Shadow
	@Final
	private int minHeight;

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
		final Collection<MetadataSectionSerializer<?>> additionalMetadata,
		final CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir
	) {
		if (RPO_BYPASS.get() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("atlases", ReuseGuard.ATLASES)) {
			return;
		}

		SpriteLoader self = (SpriteLoader)(Object)this;
		ResourceLocation atlas = this.location;
		int minWidth = this.minWidth;
		int minHeight = this.minHeight;
		AtlasReuse.Key key = new AtlasReuse.Key(atlasInfoLocation, maxMipmapLevels, this.maxSupportedTextureSize, Set.copyOf(additionalMetadata));
		AtlasReuse.Entry current = AtlasReuse.uploaded(atlas);
		CompletableFuture<Boolean> unchanged = current != null && current.key().equals(key) && current.sameSize(minWidth, minHeight)
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
				AtlasReuse.built(atlas, new AtlasReuse.Entry(key, minWidth, minHeight, recording, preparations));
				return preparations;
			});
		}));
	}

	@Inject(method = "stitch", at = @At("HEAD"))
	private void rpo$runStaleMipmaps(final List<?> sprites, final int maxMipLevel, final Executor executor, final CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
		DeferredMipmaps stale = RPO_MIPMAPS.get();
		RPO_MIPMAPS.remove();
		if (stale != null) {
			stale.runVanilla();
		}
	}

	@WrapOperation(
		method = "stitch",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Void> rpo$deferMipmaps(final Runnable task, final Executor executor, final Operation<CompletableFuture<Void>> original) {
		DeferredMipmaps deferred = new DeferredMipmaps(task, executor, new CompletableFuture<>());
		RPO_MIPMAPS.set(deferred);
		return deferred.placeholder();
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
		DeferredMipmaps deferred = RPO_MIPMAPS.get();
		RPO_MIPMAPS.remove();
		CompletableFuture<Void> mipmaps;
		if (deferred == null) {
			mipmaps = readyForUpload;
		} else if (deferred.placeholder() != readyForUpload || mipLevel <= 0) {
			deferred.runVanilla();
			mipmaps = CompletableFuture.allOf(readyForUpload, deferred.placeholder());
		} else {
			try {
				mipmaps = rpo$generateMipmaps(regions, mipLevel, deferred.executor());
			} catch (RuntimeException e) {
				deferred.runVanilla();
				mipmaps = deferred.placeholder();
			}
		}

		return original.call(width, height, mipLevel, missing, regions, AtlasBuilder.buildAfter(mipmaps, this.location, regions, width, height, mipLevel, executor));
	}

	@Unique
	private static CompletableFuture<Void> rpo$generateMipmaps(final Map<ResourceLocation, TextureAtlasSprite> regions, final int mipLevel, final Executor executor) {
		List<TextureAtlasSprite> sprites = new ArrayList<>(regions.values());
		int chunkCount = Math.min(sprites.size(), Math.max(1, Runtime.getRuntime().availableProcessors() * 2));
		if (chunkCount == 0) {
			return CompletableFuture.completedFuture(null);
		}

		List<List<TextureAtlasSprite>> chunks = new ArrayList<>(chunkCount);
		for (int i = 0; i < chunkCount; i++) {
			chunks.add(new ArrayList<>());
		}

		for (int i = 0; i < sprites.size(); i++) {
			chunks.get(i % chunkCount).add(sprites.get(i));
		}

		CompletableFuture<?>[] tasks = new CompletableFuture<?>[chunkCount];
		for (int i = 0; i < chunkCount; i++) {
			List<TextureAtlasSprite> chunk = chunks.get(i);
			tasks[i] = CompletableFuture.runAsync(() -> {
				for (TextureAtlasSprite sprite : chunk) {
					sprite.contents().increaseMipLevel(mipLevel);
				}
			}, executor);
		}

		return CompletableFuture.allOf(tasks);
	}
}
