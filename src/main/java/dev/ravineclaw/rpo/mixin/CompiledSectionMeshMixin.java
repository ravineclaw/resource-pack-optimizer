package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements TerrainHandoff.Stamped {
	@Unique
	private volatile @Nullable Object rpo$epoch;

	@Override
	public @Nullable Object rpo$epoch() {
		return this.rpo$epoch;
	}

	@Override
	public void rpo$setEpoch(final @Nullable Object epoch) {
		this.rpo$epoch = epoch;
	}
}
