package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.mixin.NativeImageAccessor;
import dev.ravineclaw.rpo.mixin.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class AtlasBuilder {
	private static final long MAX_ATLAS_PIXELS = 8192L * 4096L;
	private static final int TILE_SIZE = 256;
	private static final Map<ResourceLocation, Built> PENDING = new HashMap<>();

	private AtlasBuilder() {
	}

	public record Tile(int x, int y, NativeImage image) {
	}

	public record Level(int width, int height, List<Tile> tiles) {
	}

	public record Built(Map<ResourceLocation, TextureAtlasSprite> regions, Level[] levels) {
		public void close() {
			for (Level level : this.levels) {
				if (level != null) {
					for (Tile tile : level.tiles()) {
						tile.image().close();
					}
				}
			}
		}
	}

	public static CompletableFuture<Void> buildAfter(
		final CompletableFuture<Void> mipmapsReady,
		final ResourceLocation atlas,
		final Map<ResourceLocation, TextureAtlasSprite> regions,
		final int width,
		final int height,
		final int mipLevel,
		final Executor executor
	) {
		synchronized (PENDING) {
			Built stale = PENDING.remove(atlas);
			if (stale != null) {
				stale.close();
			}
		}

		if ((long)width * height > MAX_ATLAS_PIXELS || width <= 0 || height <= 0) {
			return mipmapsReady;
		}

		return mipmapsReady.thenComposeAsync(unused -> {
			List<TextureAtlasSprite> sprites = staticSprites(regions, mipLevel);
			if (sprites == null) {
				return CompletableFuture.completedFuture(null);
			}

			Level[] levels = new Level[mipLevel + 1];
			List<CompletableFuture<Boolean>> tasks = new ArrayList<>(mipLevel + 1);
			for (int level = 0; level <= mipLevel; level++) {
				final int l = level;
				tasks.add(CompletableFuture.supplyAsync(() -> {
					try {
						levels[l] = buildLevel(sprites, width, height, l);
						return levels[l] != null;
					} catch (Throwable t) {
						ResourcePackOptimizer.LOGGER.warn("Couldn't prebuild atlas {} level {}, using vanilla upload", atlas, l, t);
						return false;
					}
				}, executor));
			}

			return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).handle((ignored, throwable) -> {
				boolean ok = throwable == null && tasks.stream().allMatch(CompletableFuture::join);
				Built built = new Built(regions, levels);
				if (!ok) {
					built.close();
					return null;
				}

				synchronized (PENDING) {
					Built previous = PENDING.put(atlas, built);
					if (previous != null) {
						previous.close();
					}
				}

				return null;
			});
		}, executor);
	}

	public static @Nullable Built take(final ResourceLocation atlas, final Map<ResourceLocation, TextureAtlasSprite> regions) {
		synchronized (PENDING) {
			Built built = PENDING.remove(atlas);
			if (built == null) {
				return null;
			}

			if (built.regions() != regions) {
				built.close();
				return null;
			}

			return built;
		}
	}

	public static boolean isStatic(final TextureAtlasSprite sprite) {
		return !((AnimatedSprite)sprite.contents()).rpo$isAnimated();
	}

	private static @Nullable List<TextureAtlasSprite> staticSprites(final Map<ResourceLocation, TextureAtlasSprite> regions, final int mipLevel) {
		int alignment = 1 << mipLevel;
		List<TextureAtlasSprite> result = new ArrayList<>(regions.size());
		for (TextureAtlasSprite sprite : regions.values()) {
			if (!isStatic(sprite)) {
				continue;
			}

			SpriteContents contents = sprite.contents();
			NativeImage[] mips = ((SpriteContentsAccessor)contents).rpo$getByMipLevel();
			if (contents.width() % alignment != 0
				|| contents.height() % alignment != 0
				|| sprite.getX() % alignment != 0
				|| sprite.getY() % alignment != 0
				|| mips == null
				|| mips.length != mipLevel + 1) {
				return null;
			}

			for (int level = 0; level <= mipLevel; level++) {
				NativeImage image = mips[level];
				if (image == null
					|| image.format() != NativeImage.Format.RGBA
					|| image.getWidth() != contents.width() >> level
					|| image.getHeight() != contents.height() >> level
					|| ((NativeImageAccessor)(Object)image).rpo$getPixels() == 0L) {
					return null;
				}
			}

			result.add(sprite);
		}

		return result;
	}

	private static @Nullable Level buildLevel(final List<TextureAtlasSprite> sprites, final int atlasWidth, final int atlasHeight, final int level) {
		int levelWidth = Math.max(1, atlasWidth >> level);
		int levelHeight = Math.max(1, atlasHeight >> level);
		int tilesX = (levelWidth + TILE_SIZE - 1) / TILE_SIZE;
		int tilesY = (levelHeight + TILE_SIZE - 1) / TILE_SIZE;
		boolean[] used = new boolean[tilesX * tilesY];

		try (NativeImage target = new NativeImage(NativeImage.Format.RGBA, levelWidth, levelHeight, true)) {
			long targetBase = ((NativeImageAccessor)(Object)target).rpo$getPixels();
			long targetStride = (long)levelWidth * 4L;

			for (TextureAtlasSprite sprite : sprites) {
				SpriteContents contents = sprite.contents();
				NativeImage source = ((SpriteContentsAccessor)contents).rpo$getByMipLevel()[level];
				int sourceWidth = contents.width() >> level;
				int sourceHeight = contents.height() >> level;
				if (sourceWidth <= 0 || sourceHeight <= 0) {
					continue;
				}

				int originX = sprite.getX() >> level;
				int originY = sprite.getY() >> level;
				if (originX < 0 || originY < 0 || originX + sourceWidth > levelWidth || originY + sourceHeight > levelHeight) {
					return null;
				}

				long sourceBase = ((NativeImageAccessor)(Object)source).rpo$getPixels();
				long sourceStride = (long)sourceWidth * 4L;
				for (int dy = 0; dy < sourceHeight; dy++) {
					MemoryUtil.memCopy(sourceBase + dy * sourceStride, targetBase + (originY + dy) * targetStride + (long)originX * 4L, sourceStride);
				}

				for (int ty = originY / TILE_SIZE; ty <= (originY + sourceHeight - 1) / TILE_SIZE; ty++) {
					for (int tx = originX / TILE_SIZE; tx <= (originX + sourceWidth - 1) / TILE_SIZE; tx++) {
						used[ty * tilesX + tx] = true;
					}
				}
			}

			List<Tile> tiles = new ArrayList<>();
			try {
				for (int ty = 0; ty < tilesY; ty++) {
					for (int tx = 0; tx < tilesX; tx++) {
						if (!used[ty * tilesX + tx]) {
							continue;
						}

						int x = tx * TILE_SIZE;
						int y = ty * TILE_SIZE;
						int width = Math.min(TILE_SIZE, levelWidth - x);
						int height = Math.min(TILE_SIZE, levelHeight - y);
						NativeImage tile = new NativeImage(NativeImage.Format.RGBA, width, height, false);
						tiles.add(new Tile(x, y, tile));
						long rowBytes = (long)width * 4L;
						long tileBase = ((NativeImageAccessor)(Object)tile).rpo$getPixels();
						for (int row = 0; row < height; row++) {
							MemoryUtil.memCopy(targetBase + (y + row) * targetStride + (long)x * 4L, tileBase + row * rowBytes, rowBytes);
						}
					}
				}
			} catch (Throwable t) {
				for (Tile tile : tiles) {
					tile.image().close();
				}

				throw t;
			}

			return new Level(levelWidth, levelHeight, tiles);
		}
	}
}
