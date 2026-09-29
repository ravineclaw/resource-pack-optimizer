package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.ZipEntrySupplier;
import dev.ravineclaw.rpo.ZipIndex;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin {
	@Shadow
	private String addPrefix(final String path) {
		throw new AssertionError();
	}

	@Redirect(method = "listResources", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
	private Enumeration<? extends ZipEntry> rpo$indexedListEntries(
		final ZipFile zipFile, final PackType type, final String namespace, final String directory, final PackResources.ResourceOutput output
	) {
		if (!RpoSettings.active()) {
			return zipFile.entries();
		}

		String prefix = this.addPrefix(type.getDirectory() + "/" + namespace + "/") + directory + "/";
		return ZipIndex.of(zipFile).entriesWithPrefix(prefix);
	}

	@Redirect(method = "getNamespaces", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
	private Enumeration<? extends ZipEntry> rpo$indexedNamespaceEntries(final ZipFile zipFile, final PackType type) {
		if (!RpoSettings.active()) {
			return zipFile.entries();
		}

		return ZipIndex.of(zipFile).entriesWithPrefix(this.addPrefix(type.getDirectory() + "/"));
	}

	@WrapOperation(
		method = {"getResource(Ljava/lang/String;)Lnet/minecraft/server/packs/resources/IoSupplier;", "listResources"},
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/IoSupplier;create(Ljava/util/zip/ZipFile;Ljava/util/zip/ZipEntry;)Lnet/minecraft/server/packs/resources/IoSupplier;"
		)
	)
	private IoSupplier<InputStream> rpo$inspectableSupplier(final ZipFile zipFile, final ZipEntry entry, final Operation<IoSupplier<InputStream>> original) {
		if (!RpoSettings.active()) {
			return original.call(zipFile, entry);
		}

		return new ZipEntrySupplier(zipFile, entry);
	}
}
