package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ViewArea.class)
public abstract class ViewAreaMixin implements TerrainHandoff.Area {
	@Shadow
	@Final
	private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

	@Override
	public Iterable<SectionRenderDispatcher.RenderSection> rpo$sections() {
		return this.sections;
	}
}
