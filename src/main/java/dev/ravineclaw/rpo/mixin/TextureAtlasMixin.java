package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.AtlasStaging;
import dev.ravineclaw.rpo.FramePump;
import dev.ravineclaw.rpo.ModCompat;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.RpoSettings;
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
		this.rpo$tilesUploaded = false;
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
		ModCompat.atlasSwapped((TextureAtlas)(Object)this);
		FramePump.closeLater(old);
		return true;
	}

	@Inject(
		method = "upload",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/TextureUtil;prepareImage(IIII)V", shift = At.Shift.AFTER)
	)
	private void rpo$uploadPrebuilt(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		AtlasBuilder.Built built = this.rpo$prebuilt;
		this.rpo$prebuilt = null;
		if (built == null) {
			return;
		}

		try {
			AtlasBuilder.Level[] levels = built.levels();
			if (levels.length != preparations.mipLevel() + 1) {
				return;
			}

			for (int level = 0; level < levels.length; level++) {
				if (levels[level].width() != Math.max(1, preparations.width() >> level) || levels[level].height() != Math.max(1, preparations.height() >> level)) {
					return;
				}
			}

			boolean mipmap = preparations.mipLevel() > 0;
			for (int level = 0; level < levels.length; level++) {
				for (AtlasBuilder.Tile tile : levels[level].tiles()) {
					tile.image().upload(level, tile.x(), tile.y(), 0, 0, tile.image().getWidth(), tile.image().getHeight(), mipmap, false);
				}
			}

			this.rpo$tilesUploaded = true;
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Fast upload of atlas {} failed, falling back to vanilla", this.location, e);
		} finally {
			built.close();
		}
	}

	@Redirect(
		method = "upload",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame()V")
	)
	private void rpo$skipPrebuiltSprite(final TextureAtlasSprite sprite) {
		if (!this.rpo$tilesUploaded || !AtlasBuilder.isStatic(sprite)) {
			sprite.uploadFirstFrame();
		}
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void rpo$rememberUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		this.rpo$tilesUploaded = false;
		if (this.rpo$prebuilt != null) {
			this.rpo$prebuilt.close();
			this.rpo$prebuilt = null;
		}

		if (RpoSettings.active()) {
			AtlasReuse.uploadFinished(this.location, preparations);
		}
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void rpo$forgetContents(final CallbackInfo ci) {
		AtlasReuse.contentsCleared(this.location);
		AtlasStaging.discard(this.location);
	}
}
