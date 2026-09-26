package dev.ravineclaw.rpo.mixin;

import java.util.List;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompositePackResources.class)
public interface CompositePackResourcesAccessor {
	@Accessor("packResourcesStack")
	List<PackResources> rpo$getPackResourcesStack();
}
