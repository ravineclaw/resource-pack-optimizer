package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class SectionCompileTaskMixin {
	@WrapOperation(method = "doTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
	private SectionCompiler.Results rpo$rememberCompiler(
		final SectionCompiler compiler,
		final SectionPos pos,
		final RenderSectionRegion region,
		final VertexSorting sorting,
		final SectionBufferBuilderPack buffers,
		final Operation<SectionCompiler.Results> original,
		@Share("compiler") final LocalRef<SectionCompiler> used
	) {
		used.set(compiler);
		return original.call(compiler, pos, region, sorting, buffers);
	}

	@ModifyExpressionValue(method = "doTask", at = @At(value = "NEW", target = "net/minecraft/client/renderer/chunk/CompiledSectionMesh"))
	private CompiledSectionMesh rpo$stamp(final CompiledSectionMesh mesh, @Share("compiler") final LocalRef<SectionCompiler> used) {
		((TerrainHandoff.Stamped)mesh).rpo$setCompiler(used.get());
		return mesh;
	}
}
