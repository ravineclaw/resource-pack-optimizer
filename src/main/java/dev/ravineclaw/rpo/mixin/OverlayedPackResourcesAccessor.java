package dev.ravineclaw.rpo.mixin;

import java.util.List;
import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OverlayedPackResources.class)
public interface OverlayedPackResourcesAccessor {
	@Accessor("packResourcesStack")
	List<PackResources> rpo$getPackResourcesStack();
}
