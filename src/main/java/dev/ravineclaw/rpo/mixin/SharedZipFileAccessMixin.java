package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ZipAccess;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.server.packs.FilePackResources$SharedZipFileAccess")
public abstract class SharedZipFileAccessMixin implements ZipAccess {
	@Shadow
	abstract @Nullable ZipFile getOrCreateZipFile();

	@Override
	public @Nullable ZipFile rpo$getOrCreateZipFile() {
		return this.getOrCreateZipFile();
	}
}
