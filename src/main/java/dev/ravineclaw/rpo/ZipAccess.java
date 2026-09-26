package dev.ravineclaw.rpo;

import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

public interface ZipAccess {
	@Nullable ZipFile rpo$getOrCreateZipFile();
}
