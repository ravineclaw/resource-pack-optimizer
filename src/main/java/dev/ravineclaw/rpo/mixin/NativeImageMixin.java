package dev.ravineclaw.rpo.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ravineclaw.rpo.SpriteCache;
import java.io.IOException;
import java.io.InputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NativeImage.class)
public abstract class NativeImageMixin {
	@Inject(method = "read(Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;", at = @At("HEAD"), cancellable = true)
	private static void rpo$cachedRead(final InputStream inputStream, final CallbackInfoReturnable<NativeImage> cir) throws IOException {
		NativeImage image = SpriteCache.read(inputStream);
		if (image != null) {
			cir.setReturnValue(image);
		}
	}
}
