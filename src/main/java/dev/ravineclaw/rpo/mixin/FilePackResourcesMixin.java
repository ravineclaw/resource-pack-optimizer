package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ZipAccess;
import dev.ravineclaw.rpo.ZipIndex;
import dev.ravineclaw.rpo.ZipPack;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin implements ZipPack {
	@Shadow
	@Final
	private String prefix;

	@Unique
	private @Nullable ZipAccess rpo$access;

	@Shadow
	private String addPrefix(final String path) {
		throw new AssertionError();
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rpo$rememberAccess(final PackLocationInfo location, final @Coerce Object access, final String prefix, final CallbackInfo ci) {
		this.rpo$access = access instanceof ZipAccess zipAccess ? zipAccess : null;
	}

	@Override
	public @Nullable ZipFile rpo$zipFile() {
		ZipAccess access = this.rpo$access;
		if (access == null) {
			throw new IllegalStateException("Unknown zip access");
		}

		return access.rpo$getOrCreateZipFile();
	}

	@Override
	public String rpo$prefix() {
		return this.prefix;
	}

	@Redirect(method = "listResources", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
	private Enumeration<? extends ZipEntry> rpo$indexedListEntries(
		final ZipFile zipFile, final PackType type, final String namespace, final String directory, final PackResources.ResourceOutput output
	) {
		String prefix = this.addPrefix(type.getDirectory() + "/" + namespace + "/") + directory + "/";
		return ZipIndex.of(zipFile).entriesWithPrefix(prefix);
	}

	@Redirect(method = "getNamespaces", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
	private Enumeration<? extends ZipEntry> rpo$indexedNamespaceEntries(final ZipFile zipFile, final PackType type) {
		return ZipIndex.of(zipFile).entriesWithPrefix(this.addPrefix(type.getDirectory() + "/"));
	}
}
