package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class RpoSettings {
	private static final String KEY = "enabled";
	private static final boolean MIXINS_DISABLED = Boolean.getBoolean("rpo.disable");
	private static volatile boolean requested = !MIXINS_DISABLED && enabledInFile();
	private static volatile boolean active = requested;

	private RpoSettings() {
	}

	public static boolean mixinsDisabled() {
		return MIXINS_DISABLED;
	}

	public static boolean active() {
		return active;
	}

	public static boolean requested() {
		return requested;
	}

	public static void request(final boolean on) {
		requested = on && !MIXINS_DISABLED;
	}

	public static void beginReload() {
		boolean want = requested;
		if (want == active) {
			return;
		}

		active = want;
		if (want) {
			ResourcePackOptimizer.LOGGER.info("Resource Pack Optimizer turned on");
			SpriteDiskCache.enable();
			SpirvDiskCache.enable();
		} else {
			ResourcePackOptimizer.LOGGER.info("Resource Pack Optimizer turned off, reloading like vanilla");
			AtlasReuse.clear();
			AtlasBuilder.clear();
			AtlasStaging.discardAll();
			SpriteCache.clear();
			SpirvCache.clear();
			SpirvDiskCache.clear();
			SpriteDiskCache.deleteLater();
		}
	}

	public static boolean enabledInFile() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) {
				return true;
			}

			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			}

			return !"false".equalsIgnoreCase(properties.getProperty(KEY, "true").trim());
		} catch (IOException | RuntimeException e) {
			return true;
		}
	}

	public static void setEnabled(final boolean enabled) {
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Properties properties = new Properties();
			properties.setProperty(KEY, Boolean.toString(enabled));
			try (Writer writer = Files.newBufferedWriter(file)) {
				properties.store(writer, null);
			}
		} catch (IOException | RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Couldn't save the on/off setting", e);
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("resource_pack_optimizer.properties");
	}
}
