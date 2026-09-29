package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;

public record FixedFileSupplier(String origin, String name, IoSupplier<InputStream> delegate) implements IoSupplier<InputStream> {
	private static volatile @Nullable String gameJar;
	private static volatile boolean gameJarSearched;

	@Override
	public InputStream get() throws IOException {
		return this.delegate.get();
	}

	public static @Nullable String origin(final String packId) {
		String jar = gameJar();
		return jar == null ? null : "fixed|" + packId + "|" + jar;
	}

	private static @Nullable String gameJar() {
		if (!gameJarSearched) {
			synchronized (FixedFileSupplier.class) {
				if (!gameJarSearched) {
					gameJar = findGameJar();
					gameJarSearched = true;
				}
			}
		}

		return gameJar;
	}

	private static @Nullable String findGameJar() {
		try {
			Path jar = Path.of(Minecraft.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
			BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
			if (!attributes.isRegularFile()) {
				return null;
			}

			List<String> mods = new ArrayList<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				mods.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
			}

			mods.sort(null);
			return jar + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis() + "|" + Long.toHexString(PackFingerprints.hashString(String.join(",", mods)));
		} catch (Exception e) {
			return null;
		}
	}
}
