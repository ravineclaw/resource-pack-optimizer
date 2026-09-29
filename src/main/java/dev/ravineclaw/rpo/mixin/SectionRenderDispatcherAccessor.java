package dev.ravineclaw.rpo.mixin;

import java.util.Queue;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.class)
public interface SectionRenderDispatcherAccessor {
	@Accessor("toClose")
	Queue<SectionMesh> rpo$toClose();
}
