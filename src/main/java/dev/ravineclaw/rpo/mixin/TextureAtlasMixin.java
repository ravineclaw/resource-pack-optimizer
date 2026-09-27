package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.AtlasStaging;
import dev.ravineclaw.rpo.FramePump;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin extends AbstractTexture {
	@Shadow
	@Final
	private ResourceLocation location;
	@Shadow
	private List<SpriteContents> sprites;
	@Shadow
	private List<TextureAtlasSprite.Ticker> animatedTextures;
	@Shadow
	private Map<ResourceLocation, TextureAtlasSprite> texturesByName;
	@Shadow
	@Nullable
	private TextureAtlasSprite missingSprite;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private int mipLevel;

	@Unique
	private AtlasBuilder.@Nullable Built rpo$prebuilt;
	@Unique
	private boolean rpo$tilesWritten;
	@Unique
	private boolean rpo$uploading;

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
	private void rpo$takePrebuilt(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		if (AtlasReuse.isUploaded(this.location, preparations)) {
			ReloadChanges.unchanged("atlas:" + this.location);
			ci.cancel();
			return;
		}

		AtlasReuse.uploadStarted(this.location);
		this.rpo$uploading = true;
		this.rpo$releasePrebuilt();
		AtlasStaging.Staged staged = AtlasStaging.take(
			this.location, preparations.regions(), preparations.width(), preparations.height(), preparations.mipLevel()
		);
		if (staged != null) {
			if (this.rpo$swapIn(staged, preparations)) {
				this.rpo$uploading = false;
				AtlasReuse.uploadFinished(this.location, preparations);
				ci.cancel();
				return;
			}

			AtlasStaging.release(staged);
		}

		AtlasBuilder.Built built = AtlasBuilder.take(this.location, preparations.regions());
		if (built != null && !rpo$matches(built, preparations)) {
			built.close();
			built = null;
		}

		this.rpo$prebuilt = built;
	}

	@Unique
	private boolean rpo$swapIn(final AtlasStaging.Staged staged, final SpriteLoader.Preparations preparations) {
		Map<ResourceLocation, TextureAtlasSprite> byName = Map.copyOf(preparations.regions());
		TextureAtlasSprite missing = byName.get(MissingTextureAtlasSprite.getLocation());
		if (missing == null) {
			return false;
		}

		List<SpriteContents> contents = new ArrayList<>();
		List<TextureAtlasSprite.Ticker> tickers = new ArrayList<>();
		for (TextureAtlasSprite sprite : preparations.regions().values()) {
			contents.add(sprite.contents());
			TextureAtlasSprite.Ticker ticker = sprite.createTicker();
			if (ticker != null) {
				tickers.add(ticker);
			}
		}

		List<AutoCloseable> old = new ArrayList<>(this.sprites);
		old.addAll(this.animatedTextures);
		if (this.id != -1) {
			old.add(AtlasStaging.releaseId(this.id));
		}

		this.id = staged.id();
		this.width = preparations.width();
		this.height = preparations.height();
		this.mipLevel = preparations.mipLevel();
		this.setFilter(false, this.mipLevel > 0);
		this.texturesByName = byName;
		this.missingSprite = missing;
		this.sprites = List.copyOf(contents);
		this.animatedTextures = List.copyOf(tickers);
		FramePump.closeLater(old);
		return true;
	}

	@Unique
	private static boolean rpo$matches(final AtlasBuilder.Built built, final SpriteLoader.Preparations preparations) {
		AtlasBuilder.Level[] levels = built.levels();
		if (levels.length != preparations.mipLevel() + 1) {
			return false;
		}

		for (int level = 0; level < levels.length; level++) {
			if (levels[level].width() != Math.max(1, preparations.width() >> level) || levels[level].height() != Math.max(1, preparations.height() >> level)) {
				return false;
			}
		}

		return true;
	}

	@WrapOperation(
		method = "upload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame()V"
		)
	)
	private void rpo$uploadPrebuilt(final TextureAtlasSprite sprite, final Operation<Void> original) {
		AtlasBuilder.Built built = this.rpo$prebuilt;
		if (built != null && !this.rpo$tilesWritten) {
			try {
				GlStateManager._bindTexture(this.getId());
				GlStateManager._pixelStore(3314, 0);
				GlStateManager._pixelStore(3316, 0);
				GlStateManager._pixelStore(3315, 0);
				NativeImage.Format.RGBA.setUnpackPixelStoreState();
				AtlasBuilder.Level[] levels = built.levels();
				for (int level = 0; level < levels.length; level++) {
					for (AtlasBuilder.Tile tile : levels[level].tiles()) {
						GlStateManager._texSubImage2D(
							3553, level, tile.x(), tile.y(), tile.width(), tile.height(), NativeImage.Format.RGBA.glFormat(), 5121, MemoryUtil.memAddress(tile.pixels())
						);
					}
				}

				GlStateManager._texParameter(3553, 10241, levels.length > 1 ? 9986 : 9728);
				GlStateManager._texParameter(3553, 10240, 9728);

				this.rpo$tilesWritten = true;
			} catch (RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Fast upload of atlas {} failed, falling back to vanilla", this.location, e);
				this.rpo$releasePrebuilt();
				built = null;
			}
		}

		if (built != null && built.covered().contains(sprite)) {
			return;
		}

		original.call(sprite);
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void rpo$rememberUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		this.rpo$uploading = false;
		this.rpo$releasePrebuilt();
		AtlasReuse.uploadFinished(this.location, preparations);
	}

	@Unique
	private void rpo$releasePrebuilt() {
		if (this.rpo$prebuilt != null) {
			this.rpo$prebuilt.close();
			this.rpo$prebuilt = null;
		}

		this.rpo$tilesWritten = false;
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void rpo$forget(final CallbackInfo ci) {
		if (!this.rpo$uploading) {
			AtlasReuse.forget(this.location);
			AtlasStaging.discard(this.location);
		}
	}
}
