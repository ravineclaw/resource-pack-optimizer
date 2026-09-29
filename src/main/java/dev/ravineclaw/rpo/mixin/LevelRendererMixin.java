package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.ravineclaw.rpo.TerrainHandoff;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.level.block.LeavesBlock;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private ModelManager modelManager;
	@Shadow
	private @Nullable ViewArea viewArea;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	public abstract CloudRenderer cloudRenderer();

	@Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"), cancellable = true)
	private void rpo$keepMeshesUntilRebuilt(final ClientLevel level, final Options options, final Camera camera, final BlockColors blockColors, final CallbackInfo ci) {
		SectionRenderDispatcher sections = this.sectionRenderDispatcher;
		ViewArea area = this.viewArea;
		if (sections == null || area == null || area.getViewDistance() != options.getEffectiveRenderDistance()) {
			TerrainHandoff.abort();
			return;
		}

		SectionCompiler compiler = new SectionCompiler(
			options.ambientOcclusion().get(),
			options.cutoutLeaves().get(),
			this.modelManager.getBlockStateModelSet(),
			this.modelManager.getFluidStateModelSet(),
			blockColors
		);
		if (!TerrainHandoff.start(compiler, sections, area)) {
			TerrainHandoff.abort();
			return;
		}

		sections.setCompiler(compiler);
		this.cloudRenderer().markForRebuild();
		LeavesBlock.setCutoutLeaves(options.cutoutLeaves().get());
		sections.clearCompileQueue();
		ci.cancel();
	}

	@Inject(method = "resetLevelRenderData", at = @At("HEAD"))
	private void rpo$abortHandoff(final CallbackInfo ci) {
		TerrainHandoff.abort();
	}

	@Inject(method = {"prepareChunkRenders", "prepareChunkRendersIndirect"}, at = @At("HEAD"))
	private void rpo$handoffFrame(final CallbackInfoReturnable<?> cir) {
		TerrainHandoff.frame(this.viewArea, this.visibleSections);
	}

	@ModifyExpressionValue(
		method = {"executeSolid", "executeOit", "prepareChunkRenders", "prepareChunkRendersIndirect"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/AbstractTexture;getTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;")
	)
	private GpuTextureView rpo$terrainAtlas(final GpuTextureView atlas) {
		return TerrainHandoff.terrainAtlas(atlas);
	}
}
