package dev.ravineclaw.rpo.mixin;

import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PathPackResources.PathResourcesSupplier.class)
public interface PathResourcesSupplierAccessor {
	@Accessor("content")
	Path rpo$getContent();
}
