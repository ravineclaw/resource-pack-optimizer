package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.ravineclaw.rpo.SpirvCache;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GlslCompiler.class)
public abstract class GlslCompilerMixin {
	@WrapMethod(method = "compileToSpv")
	private SpvModule rpo$shareIdenticalCompiles(
		final String name,
		final String source,
		final ShaderType type,
		final ShaderDefines shaderDefines,
		final ShaderSource shaderSource,
		final Operation<SpvModule> original
	) throws Exception {
		return SpirvCache.compile(this, name, source, type, shaderDefines, shaderSource, () -> original.call(name, source, type, shaderDefines, shaderSource));
	}
}
