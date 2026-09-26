package dev.ravineclaw.rpo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

public record DeferredMipmaps(
	Runnable task, Executor executor, BiFunction<Runnable, Executor, CompletableFuture<Void>> vanilla, CompletableFuture<Void> placeholder
) {
	public void start(
		final Identifier atlas,
		final int width,
		final int height,
		final int mipLevel,
		final Map<Identifier, TextureAtlasSprite> regions,
		final boolean matched
	) {
		CompletableFuture<Void> work;
		try {
			work = matched ? this.parallel(atlas, width, height, mipLevel, regions) : this.vanilla.apply(this.task, this.executor);
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.warn("Couldn't schedule parallel mipmaps for {}, using vanilla", atlas, t);
			work = this.vanilla.apply(this.task, this.executor);
		}

		work.whenComplete((unused, throwable) -> {
			if (throwable != null) {
				this.placeholder.completeExceptionally(throwable);
			} else {
				this.placeholder.complete(null);
			}
		});
	}

	private CompletableFuture<Void> parallel(
		final Identifier atlas, final int width, final int height, final int mipLevel, final Map<Identifier, TextureAtlasSprite> regions
	) {
		CompletableFuture<Void> mipmaps;
		if (mipLevel > 0 && regions.size() > 1) {
			List<TextureAtlasSprite> sprites = new ArrayList<>(regions.values());
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
				}, this.executor);
			}

			mipmaps = CompletableFuture.allOf(tasks);
		} else {
			mipmaps = this.vanilla.apply(this.task, this.executor);
		}

		return AtlasBuilder.buildAfter(mipmaps, atlas, regions, width, height, mipLevel, this.executor);
	}
}
