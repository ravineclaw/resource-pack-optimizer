package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

public final class AtlasStaging {
	private static final Map<ResourceLocation, Staged> STAGED = new HashMap<>();

	private AtlasStaging() {
	}

	public static final class Staged {
		private final Map<ResourceLocation, TextureAtlasSprite> regions;
		private final int width;
		private final int height;
		private final int mipLevel;
		private final int id;

		private Staged(final Map<ResourceLocation, TextureAtlasSprite> regions, final int width, final int height, final int mipLevel, final int id) {
			this.regions = regions;
			this.width = width;
			this.height = height;
			this.mipLevel = mipLevel;
			this.id = id;
		}

		public int id() {
			return this.id;
		}

		private List<AutoCloseable> resources() {
			return List.of(releaseId(this.id));
		}
	}

	public static AutoCloseable releaseId(final int id) {
		return () -> TextureUtil.releaseTextureId(id);
	}

	public static CompletableFuture<Void> stage(
		final ResourceLocation atlas,
		final Map<ResourceLocation, TextureAtlasSprite> regions,
		final int width,
		final int height,
		final int mipLevel,
		final Executor executor
	) {
		discard(atlas);
		if (!ReuseGuard.untouched("atlases", ReuseGuard.ATLASES)) {
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<Void> done = new CompletableFuture<>();
		FramePump.submit(new Job(atlas, regions, width, height, mipLevel, done, executor));
		return done;
	}

	public static @Nullable Staged take(final ResourceLocation atlas, final Map<ResourceLocation, TextureAtlasSprite> regions, final int width, final int height, final int mipLevel) {
		Staged staged;
		synchronized (STAGED) {
			staged = STAGED.remove(atlas);
		}

		if (staged == null) {
			return null;
		}

		if (staged.regions != regions || staged.width != width || staged.height != height || staged.mipLevel != mipLevel) {
			FramePump.closeLater(staged.resources());
			return null;
		}

		return staged;
	}

	public static void discard(final ResourceLocation atlas) {
		Staged staged;
		synchronized (STAGED) {
			staged = STAGED.remove(atlas);
		}

		if (staged != null) {
			FramePump.closeLater(staged.resources());
		}
	}

	public static void discardAll() {
		List<Staged> all;
		synchronized (STAGED) {
			all = new ArrayList<>(STAGED.values());
			STAGED.clear();
		}

		for (Staged staged : all) {
			FramePump.closeLater(staged.resources());
		}
	}

	public static void release(final Staged staged) {
		FramePump.closeLater(staged.resources());
	}

	private static void prepareFramebuffer(final int id) {
		int draw = GlStateManager._getInteger(36006);
		int read = GlStateManager._getInteger(36010);
		int framebuffer = GlStateManager.glGenFramebuffers();
		try {
			GlStateManager._glBindFramebuffer(36160, framebuffer);
			GlStateManager._glFramebufferTexture2D(36160, 36064, 3553, id, 0);
			GlStateManager.glCheckFramebufferStatus(36160);
		} finally {
			GlStateManager._glBindFramebuffer(36009, draw);
			GlStateManager._glBindFramebuffer(36008, read);
			GlStateManager._glDeleteFramebuffers(framebuffer);
		}
	}

	private static final class Job implements FramePump.Step {
		private final ResourceLocation atlas;
		private final Map<ResourceLocation, TextureAtlasSprite> regions;
		private final int width;
		private final int height;
		private final int mipLevel;
		private final CompletableFuture<Void> done;
		private final Executor executor;
		private int phase;
		private AtlasBuilder.@Nullable Built built;
		private @Nullable Staged staged;
		private int level;
		private int tile;
		private List<TextureAtlasSprite> uncovered = List.of();
		private int nextSprite;

		private Job(
			final ResourceLocation atlas,
			final Map<ResourceLocation, TextureAtlasSprite> regions,
			final int width,
			final int height,
			final int mipLevel,
			final CompletableFuture<Void> done,
			final Executor executor
		) {
			this.atlas = atlas;
			this.regions = regions;
			this.width = width;
			this.height = height;
			this.mipLevel = mipLevel;
			this.done = done;
			this.executor = executor;
		}

		@Override
		public boolean run(final long deadline) {
			if (this.phase == 0 && !this.create()) {
				this.finish(false);
				return true;
			}

			if (this.phase == 1 && !this.writeTiles(deadline)) {
				return false;
			}

			if (this.phase == 2 && !this.uploadUncovered(deadline)) {
				return false;
			}

			this.finish(true);
			return true;
		}

		private boolean create() {
			this.built = AtlasBuilder.take(this.atlas, this.regions);
			if (this.built == null) {
				return false;
			}

			AtlasBuilder.Level[] levels = this.built.levels();
			if (levels.length != this.mipLevel + 1) {
				return false;
			}

			for (int l = 0; l < levels.length; l++) {
				if (levels[l].width() != Math.max(1, this.width >> l) || levels[l].height() != Math.max(1, this.height >> l)) {
					return false;
				}
			}

			int id = TextureUtil.generateTextureId();
			this.staged = new Staged(this.regions, this.width, this.height, this.mipLevel, id);
			TextureUtil.prepareImage(id, this.mipLevel, this.width, this.height);
			prepareFramebuffer(id);
			List<TextureAtlasSprite> uncovered = new ArrayList<>();
			for (TextureAtlasSprite sprite : this.regions.values()) {
				if (!AtlasBuilder.isStatic(sprite)) {
					uncovered.add(sprite);
				}
			}

			this.uncovered = uncovered;
			this.phase = 1;
			return true;
		}

		private boolean writeTiles(final long deadline) {
			AtlasBuilder.Level[] levels = this.built.levels();
			GlStateManager._bindTexture(this.staged.id);
			boolean mipmap = this.mipLevel > 0;
			while (this.level < levels.length) {
				List<AtlasBuilder.Tile> tiles = levels[this.level].tiles();
				if (this.tile >= tiles.size()) {
					this.level++;
					this.tile = 0;
					continue;
				}

				if (System.nanoTime() >= deadline) {
					return false;
				}

				AtlasBuilder.Tile next = tiles.get(this.tile++);
				next.image().upload(this.level, next.x(), next.y(), 0, 0, next.image().getWidth(), next.image().getHeight(), mipmap, false);
			}

			this.built.close();
			this.built = null;
			this.phase = 2;
			return true;
		}

		private boolean uploadUncovered(final long deadline) {
			GlStateManager._bindTexture(this.staged.id);
			while (this.nextSprite < this.uncovered.size()) {
				if (System.nanoTime() >= deadline) {
					return false;
				}

				this.uncovered.get(this.nextSprite++).uploadFirstFrame();
			}

			this.phase = 3;
			return true;
		}

		private void finish(final boolean success) {
			if (this.built != null) {
				this.built.close();
				this.built = null;
			}

			if (success && this.staged != null) {
				Staged previous;
				synchronized (STAGED) {
					previous = STAGED.put(this.atlas, this.staged);
				}

				if (previous != null) {
					release(previous);
				}
			}

			this.staged = null;
			this.complete();
		}

		@Override
		public void fail(final Throwable t) {
			ResourcePackOptimizer.LOGGER.warn("Couldn't prepare atlas {} ahead of time, uploading it normally", this.atlas, t);
			if (this.built != null) {
				this.built.close();
				this.built = null;
			}

			if (this.staged != null) {
				release(this.staged);
				this.staged = null;
			}

			this.complete();
		}

		private void complete() {
			try {
				this.executor.execute(() -> this.done.complete(null));
			} catch (RuntimeException e) {
				this.done.complete(null);
			}
		}
	}
}
