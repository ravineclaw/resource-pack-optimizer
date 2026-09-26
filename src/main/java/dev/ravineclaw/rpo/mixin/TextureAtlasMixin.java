package dev.ravineclaw.rpo.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.ravineclaw.rpo.AtlasBuilder;
import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
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
public abstract class TextureAtlasMixin {
	@Shadow
	@Final
	private ResourceLocation location;
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
	private void rpo$skipUnchanged(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		if (AtlasReuse.isUploaded(this.location, preparations)) {
			ReloadChanges.unchanged("atlas:" + this.location);
			ci.cancel();
			return;
		}

		AtlasReuse.uploadStarted(this.location);
		this.rpo$releasePrebuilt();
		this.rpo$prebuilt = AtlasBuilder.take(this.location, preparations.regions());
		this.rpo$tilesUploaded = false;
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
		AtlasReuse.uploadFinished(this.location, preparations);
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void rpo$forgetCleared(final CallbackInfo ci) {
		AtlasReuse.forgetUploaded(this.location);
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
