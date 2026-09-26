package dev.ravineclaw.rpo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

public final class DeferredMipmaps extends CompletableFuture<Void> {
	private final Supplier<CompletableFuture<Void>> vanilla;
	private final Executor executor;

	public DeferredMipmaps(final Supplier<CompletableFuture<Void>> vanilla, final Executor executor) {
		this.vanilla = vanilla;
		this.executor = executor;
	}

	public CompletableFuture<Void> start(final Map<ResourceLocation, TextureAtlasSprite> regions, final int mipLevel) {
		CompletableFuture<Void> work;
		try {
			work = mipLevel > 0 && regions.size() > 1 ? this.parallel(regions, mipLevel) : this.vanilla.get();
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Couldn't split mipmap generation, using vanilla", e);
			work = this.vanilla.get();
		}

		work.whenComplete((unused, error) -> {
			if (error == null) {
				this.complete(null);
			} else {
				this.completeExceptionally(error);
			}
		});
		return this;
	}

	private CompletableFuture<Void> parallel(final Map<ResourceLocation, TextureAtlasSprite> regions, final int mipLevel) {
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

		return CompletableFuture.allOf(tasks);
	}
}
