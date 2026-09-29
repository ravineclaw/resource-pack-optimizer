package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererAtlasMixin {
	@ModifyExpressionValue(
		method = {"executeSolid", "executeOit", "prepareChunkRenders", "prepareChunkRendersIndirect"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/AbstractTexture;getTextureView()Lcom/mojang/blaze3d/textures/GpuTextureView;")
	)
	private GpuTextureView rpo$terrainAtlas(final GpuTextureView atlas) {
		return TerrainHandoff.terrainAtlas(atlas);
	}
}
