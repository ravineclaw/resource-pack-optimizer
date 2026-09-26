package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.AnimatedSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements AnimatedSprite {
	@Unique
	private boolean rpo$animated;

	@Inject(method = "createAnimatedTexture", at = @At("RETURN"))
	private void rpo$rememberAnimated(
		final FrameSize frameSize, final int width, final int height, final AnimationMetadataSection metadata, final CallbackInfoReturnable<Object> cir
	) {
		this.rpo$animated = cir.getReturnValue() != null;
	}

	@Override
	public boolean rpo$isAnimated() {
		return this.rpo$animated;
	}
}
