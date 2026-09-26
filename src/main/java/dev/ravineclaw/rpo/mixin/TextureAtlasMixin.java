package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
	@Shadow
	@Final
	private ResourceLocation location;

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
		AtlasBuilder.Built built = AtlasBuilder.take(this.location, preparations.regions());
		if (built != null && !rpo$matches(built, preparations)) {
			built.close();
			built = null;
		}

		this.rpo$prebuilt = built;
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
			target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame(Lcom/mojang/blaze3d/textures/GpuTexture;)V"
		)
	)
	private void rpo$uploadPrebuilt(final TextureAtlasSprite sprite, final GpuTexture texture, final Operation<Void> original) {
		AtlasBuilder.Built built = this.rpo$prebuilt;
		if (built != null && !this.rpo$tilesWritten) {
			try {
				CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
				AtlasBuilder.Level[] levels = built.levels();
				for (int level = 0; level < levels.length; level++) {
					for (AtlasBuilder.Tile tile : levels[level].tiles()) {
						encoder.writeToTexture(texture, tile.pixels(), NativeImage.Format.RGBA, level, 0, tile.x(), tile.y(), tile.width(), tile.height());
					}
				}

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

		original.call(sprite, texture);
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
		}
	}
}
