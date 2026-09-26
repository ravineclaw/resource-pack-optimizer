package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.AtlasReuse;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void rpo$forgetClosedAtlas(final CallbackInfo ci) {
		if ((Object)this instanceof TextureAtlas atlas) {
			AtlasReuse.forgetUploaded(atlas.location());
		}
	}
}
