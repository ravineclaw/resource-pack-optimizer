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
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
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
	private Identifier location;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private int mipLevelCount;

	@Unique
	private AtlasBuilder.@Nullable Built rpo$prebuilt;
	@Unique
	private boolean rpo$recreating;

	@Shadow
	protected abstract void uploadAnimationFrames();

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
	private void rpo$takePrebuilt(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
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

	@Inject(method = "upload", at = @At("RETURN"))
	private void rpo$rememberUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		AtlasReuse.uploadFinished(this.location, preparations);
	}

	@WrapOperation(method = "createTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;close()V"))
	private void rpo$recreate(final TextureAtlas atlas, final Operation<Void> original) {
		this.rpo$recreating = true;
		try {
			original.call(atlas);
		} finally {
			this.rpo$recreating = false;
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void rpo$forget(final CallbackInfo ci) {
		if (!this.rpo$recreating) {
			AtlasReuse.forget(this.location);
		}
	}

	@Inject(method = "uploadInitialContents", at = @At("HEAD"), cancellable = true)
	private void rpo$uploadPrebuilt(final CallbackInfo ci) {
		AtlasBuilder.Built built = this.rpo$prebuilt;
		this.rpo$prebuilt = null;
		if (built == null) {
			return;
		}

		try {
			AtlasBuilder.Level[] levels = built.levels();
			if (levels.length != this.mipLevelCount) {
				return;
			}

			for (int level = 0; level < levels.length; level++) {
				if (levels[level].width() != Math.max(1, this.width >> level) || levels[level].height() != Math.max(1, this.height >> level)) {
					return;
				}
			}

			GpuTexture texture = ((AbstractTexture)(Object)this).getTexture();
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int level = 0; level < levels.length; level++) {
				for (AtlasBuilder.Tile tile : levels[level].tiles()) {
					encoder.writeToTexture(texture, tile.pixels(), NativeImage.Format.RGBA, level, 0, tile.x(), tile.y(), tile.width(), tile.height());
				}
			}

			this.uploadAnimationFrames();
			ci.cancel();
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Fast upload of atlas {} failed, falling back to vanilla", this.location, e);
		} finally {
			built.close();
		}
	}
}
