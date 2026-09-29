package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.resources.IoSupplier;

public record ZipEntrySupplier(ZipFile zipFile, ZipEntry entry) implements IoSupplier<InputStream> {
	@Override
	public InputStream get() throws IOException {
		return this.zipFile.getInputStream(this.entry);
	}
}
