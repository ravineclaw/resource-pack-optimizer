package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReuseGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
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
	private Identifier location;
	@Shadow
	@Final
	private int maxSupportedTextureSize;

	@Inject(method = "loadAndStitch", at = @At("HEAD"), cancellable = true)
	private void rpo$reuseUnchanged(
		final ResourceManager manager,
		final Identifier atlasInfoLocation,
		final int maxMipmapLevels,
		final Executor taskExecutor,
		final Set<MetadataSectionType<?>> additionalMetadata,
		final CallbackInfoReturnable<CompletableFuture<SpriteLoader.Preparations>> cir
	) {
		if (RPO_BYPASS.get() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("atlases", ReuseGuard.ATLASES)) {
			return;
		}

		SpriteLoader self = (SpriteLoader)(Object)this;
		Identifier atlas = this.location;
		Options options = Minecraft.getInstance().options;
		int anisotropyBit = options.textureFiltering().get() != TextureFilteringMethod.ANISOTROPIC ? 0 : options.maxAnisotropyBit().get();
		AtlasReuse.Key key = new AtlasReuse.Key(atlasInfoLocation, maxMipmapLevels, anisotropyBit, this.maxSupportedTextureSize, Set.copyOf(additionalMetadata));
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
		method = "stitch",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		)
	)
	private CompletableFuture<Void> rpo$parallelMipmaps(
		final Runnable task,
		final Executor executor,
		final Operation<CompletableFuture<Void>> original,
		@Local(name = "result") final Map<Identifier, TextureAtlasSprite> result,
		@Local(name = "mipLevel") final int mipLevel,
		@Local(name = "width") final int width,
		@Local(name = "height") final int height
	) {
		CompletableFuture<Void> mipmaps;
		if (mipLevel > 0 && result.size() > 1) {
			List<TextureAtlasSprite> sprites = new ArrayList<>(result.values());
			int chunkCount = Math.min(sprites.size(), Math.max(1, Runtime.getRuntime().availableProcessors() * 2));
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

			mipmaps = CompletableFuture.allOf(tasks);
		} else {
			mipmaps = original.call(task, executor);
		}

		return AtlasBuilder.buildAfter(mipmaps, this.location, result, width, height, mipLevel, executor);
	}
}
