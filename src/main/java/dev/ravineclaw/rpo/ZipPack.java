package dev.ravineclaw.rpo;

import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public interface ZipPack {
	@Nullable ZipFile rpo$zipFile();

	String rpo$prefix();
}
