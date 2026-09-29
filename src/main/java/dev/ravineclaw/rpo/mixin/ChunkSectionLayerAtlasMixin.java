package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkSectionLayer.class)
public abstract class ChunkSectionLayerAtlasMixin {
	@ModifyReturnValue(method = "textureView", at = @At("RETURN"))
	private GpuTextureView rpo$terrainAtlas(final GpuTextureView atlas) {
		return TerrainHandoff.terrainAtlas(atlas);
	}
}
