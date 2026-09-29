package dev.ravineclaw.rpo.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.CachedSprite;
import dev.ravineclaw.rpo.SpriteCache;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements CachedSprite {
	@Shadow
	@Final
	private ResourceLocation name;
	@Shadow
	@Final
	private NativeImage originalImage;
	@Shadow
	private NativeImage[] byMipLevel;
	@Unique
	private SpriteCache.@Nullable Entry rpo$cacheEntry;

	@Override
	public NativeImage rpo$originalImage() {
		return this.originalImage;
	}

	@Override
	public void rpo$setCacheEntry(final SpriteCache.@Nullable Entry entry) {
		this.rpo$cacheEntry = entry;
	}

	@Unique
	private SpriteCache.MipKey rpo$mipKey() {
		return new SpriteCache.MipKey(this.name.getPath().startsWith("item/"));
	}

	@Inject(method = "increaseMipLevel", at = @At("HEAD"), cancellable = true)
	private void rpo$cachedMips(final int mipLevel, final CallbackInfo ci) {
		SpriteCache.Entry entry = this.rpo$cacheEntry;
		if (entry == null || this.byMipLevel.length != 1 || this.byMipLevel[0] != this.originalImage || mipLevel < 0) {
			return;
		}

		NativeImage[] result = new NativeImage[mipLevel + 1];
		if (SpriteCache.serveMips(entry, this.rpo$mipKey(), mipLevel, this.originalImage, result)) {
			this.byMipLevel = result;
			this.rpo$cacheEntry = null;
			ci.cancel();
		}
	}

	@Inject(method = "increaseMipLevel", at = @At("TAIL"))
	private void rpo$storeMips(final int mipLevel, final CallbackInfo ci) {
		SpriteCache.Entry entry = this.rpo$cacheEntry;
		this.rpo$cacheEntry = null;
		if (entry != null && this.byMipLevel.length == mipLevel + 1 && this.byMipLevel[0] == this.originalImage) {
			SpriteCache.storeMips(entry, this.rpo$mipKey(), this.byMipLevel);
		}
	}
}
