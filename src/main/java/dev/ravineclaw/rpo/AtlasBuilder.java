package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class AtlasBuilder {
	private static final long MAX_ATLAS_PIXELS = 8192L * 4096L;
	private static final int TILE_SIZE = 256;
	private static final Map<ResourceLocation, Built> PENDING = new HashMap<>();

	private AtlasBuilder() {
	}

	public record Tile(int x, int y, int width, int height, ByteBuffer pixels) {
	}

	public record Level(int width, int height, List<Tile> tiles) {
	}

	public record Built(Map<ResourceLocation, TextureAtlasSprite> regions, Set<TextureAtlasSprite> covered, Level[] levels) {
		public void close() {
			for (Level level : this.levels) {
				if (level != null) {
					for (Tile tile : level.tiles()) {
						MemoryUtil.memFree(tile.pixels());
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
				Set<TextureAtlasSprite> covered = Collections.newSetFromMap(new IdentityHashMap<>(sprites.size()));
				covered.addAll(sprites);
				Built built = new Built(regions, covered, levels);
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

	private static @Nullable List<TextureAtlasSprite> staticSprites(final Map<ResourceLocation, TextureAtlasSprite> regions, final int mipLevel) {
		int alignment = 1 << mipLevel;
		List<TextureAtlasSprite> result = new ArrayList<>(regions.size());
		for (TextureAtlasSprite sprite : regions.values()) {
			SpriteContents contents = sprite.contents();
			if (contents.metadata().getSection(AnimationMetadataSection.TYPE).isPresent()) {
				continue;
			}

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
					|| image.getHeight() != contents.height() >> level) {
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
			long targetBase = target.getPointer();
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

				long sourceBase = source.getPointer();
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
			for (int ty = 0; ty < tilesY; ty++) {
				for (int tx = 0; tx < tilesX; tx++) {
					if (!used[ty * tilesX + tx]) {
						continue;
					}

					int x = tx * TILE_SIZE;
					int y = ty * TILE_SIZE;
					int width = Math.min(TILE_SIZE, levelWidth - x);
					int height = Math.min(TILE_SIZE, levelHeight - y);
					long rowBytes = (long)width * 4L;
					ByteBuffer pixels = MemoryUtil.memAlloc((int)(rowBytes * height));
					long pixelsBase = MemoryUtil.memAddress(pixels);
					for (int row = 0; row < height; row++) {
						MemoryUtil.memCopy(targetBase + (y + row) * targetStride + (long)x * 4L, pixelsBase + row * rowBytes, rowBytes);
					}

					tiles.add(new Tile(x, y, width, height, pixels));
				}
			}

			return new Level(levelWidth, levelHeight, tiles);
		}
	}
}
