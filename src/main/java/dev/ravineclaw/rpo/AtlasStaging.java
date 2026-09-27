package dev.ravineclaw.rpo;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class AtlasStaging {
	private static final Map<Identifier, Staged> STAGED = new HashMap<>();
	private static final List<RenderPipeline> ANIMATION_PIPELINES = List.of(RenderPipelines.ANIMATE_SPRITE_BLIT, RenderPipelines.ANIMATE_SPRITE_INTERPOLATE);

	private AtlasStaging() {
	}

	public static final class Staged {
		private final Map<Identifier, TextureAtlasSprite> regions;
		private final int width;
		private final int height;
		private final int mipLevel;
		private final GpuTexture texture;
		private final GpuTextureView view;
		private final GpuTextureView[] mipViews;
		private final List<SpriteContents.AnimationState> states = new ArrayList<>();
		private @Nullable GpuBuffer spriteUbos;

		private Staged(
			final Map<Identifier, TextureAtlasSprite> regions,
			final int width,
			final int height,
			final int mipLevel,
			final GpuTexture texture,
			final GpuTextureView view,
			final GpuTextureView[] mipViews
		) {
			this.regions = regions;
			this.width = width;
			this.height = height;
			this.mipLevel = mipLevel;
			this.texture = texture;
			this.view = view;
			this.mipViews = mipViews;
		}

		public GpuTexture texture() {
			return this.texture;
		}

		public GpuTextureView view() {
			return this.view;
		}

		public GpuTextureView[] mipViews() {
			return this.mipViews;
		}

		public @Nullable GpuBuffer spriteUbos() {
			return this.spriteUbos;
		}

		public List<SpriteContents.AnimationState> states() {
			return this.states;
		}

		private List<AutoCloseable> resources() {
			List<AutoCloseable> resources = new ArrayList<>(this.states);
			if (this.spriteUbos != null) {
				resources.add(this.spriteUbos);
			}

			resources.addAll(List.of(this.mipViews));
			resources.add(this.view);
			resources.add(this.texture);
			return resources;
		}
	}

	public static CompletableFuture<Void> stage(
		final Identifier atlas,
		final Map<Identifier, TextureAtlasSprite> regions,
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

	public static @Nullable Staged take(final Identifier atlas, final SpriteLoader.Preparations preparations) {
		Staged staged;
		synchronized (STAGED) {
			staged = STAGED.remove(atlas);
		}

		if (staged == null) {
			return null;
		}

		if (staged.regions != preparations.regions()
			|| staged.width != preparations.width()
			|| staged.height != preparations.height()
			|| staged.mipLevel != preparations.mipLevel()) {
			FramePump.closeLater(staged.resources());
			return null;
		}

		return staged;
	}

	private static boolean animationShadersLoaded() {
		ShaderManager shaders = Minecraft.getInstance().getShaderManager();
		for (RenderPipeline pipeline : ANIMATION_PIPELINES) {
			if (shaders.getShader(pipeline.getVertexShader(), ShaderType.VERTEX) == null
				|| shaders.getShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT) == null) {
				return false;
			}
		}

		return true;
	}

	public static void discard(final Identifier atlas) {
		Staged staged;
		synchronized (STAGED) {
			staged = STAGED.remove(atlas);
		}

		if (staged != null) {
			FramePump.closeLater(staged.resources());
		}
	}

	public static void release(final Staged staged) {
		FramePump.closeLater(staged.resources());
	}

	private static final class Job implements FramePump.Step {
		private final Identifier atlas;
		private final Map<Identifier, TextureAtlasSprite> regions;
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
		private List<TextureAtlasSprite> animated = List.of();
		private int nextSprite;
		private int spriteUboSize;
		private int uboBlockSize;

		private Job(
			final Identifier atlas,
			final Map<Identifier, TextureAtlasSprite> regions,
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

			if (this.phase == 2) {
				this.createSpriteUbos();
			}

			if (this.phase == 3 && !this.createAnimations(deadline)) {
				return false;
			}

			this.finish(true);
			return true;
		}

		private boolean create() {
			if (!animationShadersLoaded()) {
				return false;
			}

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

			GpuDevice device = RenderSystem.getDevice();
			Identifier location = this.atlas;
			GpuTexture texture = device.createTexture(location::toString, 15, TextureFormat.RGBA8, this.width, this.height, 1, this.mipLevel + 1);
			GpuTextureView[] mipViews = new GpuTextureView[this.mipLevel + 1];
			GpuTextureView view = null;
			try {
				view = device.createTextureView(texture);
				for (int l = 0; l <= this.mipLevel; l++) {
					mipViews[l] = device.createTextureView(texture, l, 1);
				}
			} catch (RuntimeException e) {
				for (GpuTextureView mipView : mipViews) {
					if (mipView != null) {
						mipView.close();
					}
				}

				if (view != null) {
					view.close();
				}

				texture.close();
				throw e;
			}

			this.staged = new Staged(this.regions, this.width, this.height, this.mipLevel, texture, view, mipViews);
			CommandEncoder encoder = device.createCommandEncoder();
			for (GpuTextureView mipView : mipViews) {
				encoder.createRenderPass(() -> "Prepare " + location, mipView, OptionalInt.empty()).close();
			}

			this.phase = 1;
			return true;
		}

		private boolean writeTiles(final long deadline) {
			AtlasBuilder.Level[] levels = this.built.levels();
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
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
				encoder.writeToTexture(this.staged.texture, next.pixels(), NativeImage.Format.RGBA, this.level, 0, next.x(), next.y(), next.width(), next.height());
			}

			this.built.close();
			this.built = null;
			this.phase = 2;
			return true;
		}

		private void createSpriteUbos() {
			List<TextureAtlasSprite> animated = new ArrayList<>();
			for (TextureAtlasSprite sprite : this.regions.values()) {
				if (sprite.contents().isAnimated()) {
					animated.add(sprite);
				}
			}

			this.phase = 4;
			if (animated.isEmpty()) {
				return;
			}

			GpuDevice device = RenderSystem.getDevice();
			this.spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getUniformOffsetAlignment());
			this.uboBlockSize = this.spriteUboSize * (this.mipLevel + 1);
			ByteBuffer buffer = MemoryUtil.memAlloc(animated.size() * this.uboBlockSize);
			try {
				for (int i = 0; i < animated.size(); i++) {
					animated.get(i).uploadSpriteUbo(buffer, i * this.uboBlockSize, this.mipLevel, this.width, this.height, this.spriteUboSize);
				}

				Identifier location = this.atlas;
				this.staged.spriteUbos = device.createBuffer(() -> location + " sprite UBOs", 128, buffer);
			} finally {
				MemoryUtil.memFree(buffer);
			}

			this.animated = animated;
			this.phase = 3;
		}

		private boolean createAnimations(final long deadline) {
			if (System.nanoTime() >= deadline) {
				return false;
			}

			List<SpriteContents.AnimationState> batch = new ArrayList<>();
			do {
				int index = this.nextSprite++;
				SpriteContents.AnimationState state = this.animated.get(index)
					.createAnimationState(this.staged.spriteUbos.slice(index * this.uboBlockSize, this.uboBlockSize), this.spriteUboSize);
				if (state != null) {
					this.staged.states.add(state);
					batch.add(state);
				}
			} while (this.nextSprite < this.animated.size() && System.nanoTime() < deadline);

			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			Identifier location = this.atlas;
			for (int l = 0; l <= this.mipLevel; l++) {
				try (RenderPass renderPass = encoder.createRenderPass(() -> "Animate " + location, this.staged.mipViews[l], OptionalInt.empty())) {
					for (SpriteContents.AnimationState state : batch) {
						if (state.needsToDraw()) {
							state.drawToAtlas(renderPass, state.getDrawUbo(l));
						}
					}
				}
			}

			if (this.nextSprite < this.animated.size()) {
				return false;
			}

			this.phase = 4;
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
