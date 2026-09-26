package dev.ravineclaw.rpo.mixin;

import java.io.File;
import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FilePackResources.FileResourcesSupplier.class)
public interface FileResourcesSupplierAccessor {
	@Accessor("content")
	File rpo$getContent();
}
