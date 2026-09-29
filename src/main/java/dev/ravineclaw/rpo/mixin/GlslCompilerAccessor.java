package dev.ravineclaw.rpo.mixin;

import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlslCompiler.class)
public interface GlslCompilerAccessor {
	@Accessor("isZeroToOne")
	boolean rpo$isZeroToOne();

	@Accessor("shaderDrawParameters")
	boolean rpo$shaderDrawParameters();
}
