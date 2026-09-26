package dev.ravineclaw.rpo.mixin;

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

	@Unique
	private AtlasBuilder.@Nullable Built rpo$prebuilt;
	@Unique
	private boolean rpo$tilesUploaded;

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
	private void rpo$takePrebuilt(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		this.rpo$tilesUploaded = false;
		if (AtlasReuse.isUploaded(this.location, preparations)) {
			ReloadChanges.unchanged("atlas:" + this.location);
			ci.cancel();
			return;
		}

		AtlasReuse.uploadStarted(this.location);
		if (this.rpo$prebuilt != null) {
			this.rpo$prebuilt.close();
		}

		this.rpo$prebuilt = AtlasBuilder.take(this.location, preparations.regions());
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
		AtlasReuse.uploadFinished(this.location, preparations);
	}

	@Inject(method = "clearTextureData", at = @At("HEAD"))
	private void rpo$forgetContents(final CallbackInfo ci) {
		AtlasReuse.contentsCleared(this.location);
	}
}
