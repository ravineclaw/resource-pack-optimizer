package dev.ravineclaw.rpo.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.AtlasStaging;
import dev.ravineclaw.rpo.FramePump;
import dev.ravineclaw.rpo.ModCompat;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.TerrainHandoff;
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
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
	private @Nullable TextureAtlasSprite missingSprite;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private int mipLevel;

	@Unique
	private AtlasBuilder.@Nullable Built rpo$prebuilt;
	@Unique
	private boolean rpo$tilesUploaded;

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
	private void rpo$takePrebuilt(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		if (this.rpo$prebuilt != null) {
			this.rpo$prebuilt.close();
			this.rpo$prebuilt = null;
		}

		if (!RpoSettings.active()) {
			AtlasReuse.forget(this.location);
			return;
		}

		if (AtlasReuse.isUploaded(this.location, preparations)) {
			ReloadChanges.unchanged("atlas:" + this.location);
			ci.cancel();
			return;
		}

		AtlasReuse.uploadStarted(this.location);
		this.rpo$releasePrebuilt();
		AtlasStaging.Staged staged = AtlasStaging.take(
			this.location, preparations.regions(), preparations.width(), preparations.height(), preparations.mipLevel()
		);
		if (staged != null) {
			if (this.rpo$swapIn(staged, preparations)) {
				AtlasReuse.uploadFinished(this.location, preparations);
				ci.cancel();
				return;
			}

			AtlasStaging.release(staged);
		}

		this.rpo$prebuilt = AtlasBuilder.take(this.location, preparations.regions());
		this.rpo$tilesUploaded = false;
	}

	@Unique
	@SuppressWarnings("deprecation")
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
		boolean handedOff = this.location.equals(TextureAtlas.LOCATION_BLOCKS)
			&& this.texture != null
			&& this.textureView != null
			&& TerrainHandoff.offer(this.texture, this.textureView);
		if (!handedOff) {
			if (this.textureView != null) {
				old.add(this.textureView);
			}

			if (this.texture != null) {
				old.add(this.texture);
			}
		}

		this.texture = staged.texture();
		this.textureView = staged.view();
		this.width = preparations.width();
		this.height = preparations.height();
		this.mipLevel = preparations.mipLevel();
		this.setFilter(false, this.mipLevel > 1);
		this.texturesByName = byName;
		this.missingSprite = missing;
		this.sprites = List.copyOf(contents);
		this.animatedTextures = List.copyOf(tickers);
		ModCompat.atlasSwapped((TextureAtlas)(Object)this);
		FramePump.closeLater(old);
		return true;
	}

	@Redirect(
		method = "upload",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame(Lcom/mojang/blaze3d/textures/GpuTexture;)V"
		)
	)
	private void rpo$uploadPrebuilt(final TextureAtlasSprite sprite, final GpuTexture texture) {
		if (this.rpo$prebuilt != null && !this.rpo$tilesUploaded) {
			this.rpo$tilesUploaded = this.rpo$uploadTiles(this.rpo$prebuilt, texture);
			if (!this.rpo$tilesUploaded) {
				this.rpo$releasePrebuilt();
			}
		}

		if (this.rpo$prebuilt == null || sprite.isAnimated()) {
			sprite.uploadFirstFrame(texture);
		}
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void rpo$rememberUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		this.rpo$releasePrebuilt();
		if (RpoSettings.active()) {
			AtlasReuse.uploadFinished(this.location, preparations);
		}
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void rpo$forgetCleared(final CallbackInfo ci) {
		AtlasReuse.forgetUploaded(this.location);
		AtlasStaging.discard(this.location);
	}

	@Unique
	private boolean rpo$uploadTiles(final AtlasBuilder.Built built, final GpuTexture texture) {
		try {
			AtlasBuilder.Level[] levels = built.levels();
			if (levels.length != this.mipLevel + 1 || texture.getMipLevels() != levels.length) {
				return false;
			}

			for (int level = 0; level < levels.length; level++) {
				if (levels[level].width() != Math.max(1, this.width >> level)
					|| levels[level].height() != Math.max(1, this.height >> level)
					|| texture.getWidth(level) != levels[level].width()
					|| texture.getHeight(level) != levels[level].height()) {
					return false;
				}
			}

			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int level = 0; level < levels.length; level++) {
				for (AtlasBuilder.Tile tile : levels[level].tiles()) {
					encoder.writeToTexture(texture, tile.pixels(), NativeImage.Format.RGBA, level, 0, tile.x(), tile.y(), tile.width(), tile.height());
				}
			}

			return true;
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Fast upload of atlas {} failed, falling back to vanilla", this.location, e);
			return false;
		}
	}

	@Unique
	private void rpo$releasePrebuilt() {
		if (this.rpo$prebuilt != null) {
			this.rpo$prebuilt.close();
			this.rpo$prebuilt = null;
		}
	}
}
