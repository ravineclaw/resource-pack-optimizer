package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.mixin.SpriteContentsAccessor;
import dev.ravineclaw.rpo.mixin.TextureAtlasSpriteAccessor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class AtlasBuilder {
	private static final long MAX_ATLAS_PIXELS = 8192L * 4096L;
	private static final int TILE_SIZE = 256;
	private static final Map<Identifier, Built> PENDING = new HashMap<>();

	private AtlasBuilder() {
	}

	public record Tile(int x, int y, int width, int height, ByteBuffer pixels) {
	}

	public record Level(int width, int height, List<Tile> tiles) {
	}

	public record Built(Map<Identifier, TextureAtlasSprite> regions, Level[] levels) {
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
		final Identifier atlas,
		final Map<Identifier, TextureAtlasSprite> regions,
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

			Plan plan = plan(sprites, width, height, mipLevel);
			if (plan == null) {
				return CompletableFuture.completedFuture(null);
			}

			int chunkCount = Math.min(plan.jobs().size(), Math.max(1, Runtime.getRuntime().availableProcessors() * 2));
			List<CompletableFuture<Boolean>> tasks = new ArrayList<>(chunkCount);
			for (int chunk = 0; chunk < chunkCount; chunk++) {
				final int first = chunk;
				tasks.add(CompletableFuture.supplyAsync(() -> {
					try {
						for (int i = first; i < plan.jobs().size(); i += chunkCount) {
							TileJob job = plan.jobs().get(i);
							plan.tiles()[job.level()][job.index()] = buildTile(job);
						}

						return true;
					} catch (Throwable t) {
						ResourcePackOptimizer.LOGGER.warn("Couldn't prebuild atlas {}, using vanilla upload", atlas, t);
						return false;
					}
				}, executor));
			}

			return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).handle((ignored, throwable) -> {
				boolean ok = throwable == null && tasks.stream().allMatch(CompletableFuture::join);
				Built built = new Built(regions, plan.levels());
				if (!ok) {
					built.close();
					return Boolean.FALSE;
				}

				synchronized (PENDING) {
					Built previous = PENDING.put(atlas, built);
					if (previous != null) {
						previous.close();
					}
				}

				return Boolean.TRUE;
			}).thenCompose(ok -> ok ? AtlasStaging.stage(atlas, regions, width, height, mipLevel, executor) : CompletableFuture.completedFuture(null));
		}, executor);
	}

	public static void clear() {
		synchronized (PENDING) {
			for (Built built : PENDING.values()) {
				built.close();
			}

			PENDING.clear();
		}
	}

	public static @Nullable Built take(final Identifier atlas, final Map<Identifier, TextureAtlasSprite> regions) {
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

	private static @Nullable List<TextureAtlasSprite> staticSprites(final Map<Identifier, TextureAtlasSprite> regions, final int mipLevel) {
		int alignment = 1 << mipLevel;
		List<TextureAtlasSprite> result = new ArrayList<>(regions.size());
		for (TextureAtlasSprite sprite : regions.values()) {
			if (sprite.contents().isAnimated()) {
				continue;
			}

			SpriteContents contents = sprite.contents();
			int padding = ((TextureAtlasSpriteAccessor)sprite).rpo$getPadding();
			NativeImage[] mips = ((SpriteContentsAccessor)contents).rpo$getByMipLevel();
			if (contents.width() % alignment != 0
				|| contents.height() % alignment != 0
				|| padding % alignment != 0
				|| sprite.getX() % alignment != 0
				|| sprite.getY() % alignment != 0
				|| mips == null
				|| mips.length <= mipLevel) {
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

	private record TileJob(int level, int index, int x, int y, int width, int height, List<TextureAtlasSprite> sprites) {
	}

	private record Plan(int[] widths, int[] heights, List<TileJob> jobs, Tile[][] tiles) {
		private Level[] levels() {
			Level[] levels = new Level[this.tiles.length];
			for (int level = 0; level < this.tiles.length; level++) {
				List<Tile> list = new ArrayList<>(this.tiles[level].length);
				for (Tile tile : this.tiles[level]) {
					if (tile != null) {
						list.add(tile);
					}
				}

				levels[level] = new Level(this.widths[level], this.heights[level], list);
			}

			return levels;
		}
	}

	private static @Nullable Plan plan(final List<TextureAtlasSprite> sprites, final int atlasWidth, final int atlasHeight, final int mipLevel) {
		int[] widths = new int[mipLevel + 1];
		int[] heights = new int[mipLevel + 1];
		List<TileJob> jobs = new ArrayList<>();
		Tile[][] tiles = new Tile[mipLevel + 1][];
		for (int level = 0; level <= mipLevel; level++) {
			int levelWidth = Math.max(1, atlasWidth >> level);
			int levelHeight = Math.max(1, atlasHeight >> level);
			int tilesX = (levelWidth + TILE_SIZE - 1) / TILE_SIZE;
			int tilesY = (levelHeight + TILE_SIZE - 1) / TILE_SIZE;
			widths[level] = levelWidth;
			heights[level] = levelHeight;
			tiles[level] = new Tile[tilesX * tilesY];
			List<List<TextureAtlasSprite>> buckets = new ArrayList<>(tilesX * tilesY);
			for (int i = 0; i < tilesX * tilesY; i++) {
				buckets.add(null);
			}

			for (TextureAtlasSprite sprite : sprites) {
				SpriteContents contents = sprite.contents();
				int sourceWidth = contents.width() >> level;
				int sourceHeight = contents.height() >> level;
				if (sourceWidth <= 0 || sourceHeight <= 0) {
					continue;
				}

				int pad = ((TextureAtlasSpriteAccessor)sprite).rpo$getPadding() >> level;
				int originX = sprite.getX() >> level;
				int originY = sprite.getY() >> level;
				int slotWidth = sourceWidth + pad * 2;
				int slotHeight = sourceHeight + pad * 2;
				if (originX < 0 || originY < 0 || originX + slotWidth > levelWidth || originY + slotHeight > levelHeight) {
					return null;
				}

				for (int ty = originY / TILE_SIZE; ty <= (originY + slotHeight - 1) / TILE_SIZE; ty++) {
					for (int tx = originX / TILE_SIZE; tx <= (originX + slotWidth - 1) / TILE_SIZE; tx++) {
						int index = ty * tilesX + tx;
						List<TextureAtlasSprite> bucket = buckets.get(index);
						if (bucket == null) {
							bucket = new ArrayList<>();
							buckets.set(index, bucket);
						}

						bucket.add(sprite);
					}
				}
			}

			for (int index = 0; index < buckets.size(); index++) {
				List<TextureAtlasSprite> bucket = buckets.get(index);
				if (bucket != null) {
					int x = index % tilesX * TILE_SIZE;
					int y = index / tilesX * TILE_SIZE;
					jobs.add(new TileJob(level, index, x, y, Math.min(TILE_SIZE, levelWidth - x), Math.min(TILE_SIZE, levelHeight - y), bucket));
				}
			}
		}

		return new Plan(widths, heights, jobs, tiles);
	}

	private static Tile buildTile(final TileJob job) {
		int level = job.level();
		long rowBytes = (long)job.width() * 4L;
		ByteBuffer pixels = MemoryUtil.memCalloc((int)(rowBytes * job.height()));
		try {
			long base = MemoryUtil.memAddress(pixels);
			int tileRight = job.x() + job.width();
			int tileBottom = job.y() + job.height();
			for (TextureAtlasSprite sprite : job.sprites()) {
				SpriteContents contents = sprite.contents();
				NativeImage source = ((SpriteContentsAccessor)contents).rpo$getByMipLevel()[level];
				int sourceWidth = contents.width() >> level;
				int sourceHeight = contents.height() >> level;
				int pad = ((TextureAtlasSpriteAccessor)sprite).rpo$getPadding() >> level;
				int originX = sprite.getX() >> level;
				int originY = sprite.getY() >> level;
				int fromX = Math.max(originX, job.x());
				int toX = Math.min(originX + sourceWidth + pad * 2, tileRight);
				int fromY = Math.max(originY, job.y());
				int toY = Math.min(originY + sourceHeight + pad * 2, tileBottom);
				long sourceBase = source.getPointer();
				long sourceStride = (long)sourceWidth * 4L;
				int leftEnd = Math.min(toX, originX + pad);
				int middleEnd = Math.min(toX, originX + pad + sourceWidth);
				for (int y = fromY; y < toY; y++) {
					int sy = Math.clamp(y - originY - pad, 0, sourceHeight - 1);
					long sourceRow = sourceBase + sy * sourceStride;
					long targetRow = base + (y - job.y()) * rowBytes - (long)job.x() * 4L;
					int x = fromX;
					if (x < leftEnd) {
						int left = MemoryUtil.memGetInt(sourceRow);
						for (; x < leftEnd; x++) {
							MemoryUtil.memPutInt(targetRow + x * 4L, left);
						}
					}

					if (x < middleEnd) {
						MemoryUtil.memCopy(sourceRow + (long)(x - originX - pad) * 4L, targetRow + x * 4L, (long)(middleEnd - x) * 4L);
						x = middleEnd;
					}

					if (x < toX) {
						int right = MemoryUtil.memGetInt(sourceRow + sourceStride - 4L);
						for (; x < toX; x++) {
							MemoryUtil.memPutInt(targetRow + x * 4L, right);
						}
					}
				}
			}
		} catch (Throwable t) {
			MemoryUtil.memFree(pixels);
			throw t;
		}

		return new Tile(job.x(), job.y(), job.width(), job.height(), pixels);
	}
}
