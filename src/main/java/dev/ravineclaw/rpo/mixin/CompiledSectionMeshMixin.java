package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements TerrainHandoff.Stamped {
	@Unique
	private volatile @Nullable SectionCompiler rpo$compiler;

	@Override
	public @Nullable SectionCompiler rpo$compiler() {
		return this.rpo$compiler;
	}

	@Override
	public void rpo$setCompiler(final @Nullable SectionCompiler compiler) {
		this.rpo$compiler = compiler;
	}
}
