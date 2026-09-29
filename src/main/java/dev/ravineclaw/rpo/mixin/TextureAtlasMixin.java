package dev.ravineclaw.rpo.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
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
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
public abstract class TextureAtlasMixin extends AbstractTexture {
	@Shadow
	@Final
	private Identifier location;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private int mipLevelCount;
	@Shadow
	private int maxMipLevel;
	@Shadow
	private List<TextureAtlasSprite> sprites;
	@Shadow
	private List<SpriteContents.AnimationState> animatedTexturesStates;
	@Shadow
	private Map<Identifier, TextureAtlasSprite> texturesByName;
	@Shadow
	private @Nullable TextureAtlasSprite missingSprite;
	@Shadow
	private GpuTextureView[] mipViews;
	@Shadow
	private @Nullable GpuBuffer spriteUbos;

	@Unique
	private AtlasBuilder.@Nullable Built rpo$prebuilt;
	@Unique
	private boolean rpo$recreating;

	@Shadow
	protected abstract void uploadAnimationFrames();

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

		AtlasStaging.Staged staged = AtlasStaging.take(this.location, preparations);
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
		Map<Identifier, TextureAtlasSprite> byName = Map.copyOf(preparations.regions());
		TextureAtlasSprite missing = byName.get(MissingTextureAtlasSprite.getLocation());
		if (missing == null || SharedConstants.DEBUG_DUMP_TEXTURE_ATLAS) {
			return false;
		}

		List<AutoCloseable> old = new ArrayList<>(this.sprites);
		old.addAll(this.animatedTexturesStates);
		if (this.spriteUbos != null) {
			old.add(this.spriteUbos);
		}

		old.addAll(List.of(this.mipViews));
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
		this.maxMipLevel = preparations.mipLevel();
		this.mipLevelCount = preparations.mipLevel() + 1;
		this.mipViews = staged.mipViews();
		this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		this.texturesByName = byName;
		this.missingSprite = missing;
		this.sprites = ImmutableList.copyOf(preparations.regions().values());
		this.spriteUbos = staged.spriteUbos();
		this.animatedTexturesStates = ImmutableList.copyOf(staged.states());
		ModCompat.atlasSwapped((TextureAtlas)(Object)this);
		FramePump.closeLater(old);
		return true;
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void rpo$rememberUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		if (RpoSettings.active()) {
			AtlasReuse.uploadFinished(this.location, preparations);
		}
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
			AtlasStaging.discard(this.location);
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
