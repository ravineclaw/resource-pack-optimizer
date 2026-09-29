package dev.ravineclaw.rpo.mixin;

import java.io.InputStream;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Resource.class)
public interface ResourceAccessor {
	@Accessor("streamSupplier")
	IoSupplier<InputStream> rpo$getStreamSupplier();
}
