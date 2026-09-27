package dev.ravineclaw.rpo.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {
	@Accessor("wrapS")
	void rpo$setWrapS(int wrapS);

	@Accessor("wrapT")
	void rpo$setWrapT(int wrapT);

	@Accessor("minFilter")
	void rpo$setMinFilter(int minFilter);

	@Accessor("magFilter")
	void rpo$setMagFilter(int magFilter);
}
