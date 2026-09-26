package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.CompositePackResourcesAccessor;
import dev.ravineclaw.rpo.mixin.PathPackResourcesAccessor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.VanillaPackResources;

public final class PackFingerprints {
	public static final long ABSENT = -1L;
	public static final long UNKNOWN = Long.MIN_VALUE;
	private static final long DIRECTORY = -2L;
	private static final long IMMUTABLE_BASE = -16L;

	private static final Map<PackResources, Source> SOURCES = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<String, Integer> IMMUTABLE_IDS = new ConcurrentHashMap<>();
	private static final Source UNKNOWN_SOURCE = (type, id, path, out) -> out.add(UNKNOWN);
	private static final Map<String, List<Path>> FAILED_INDEX = new HashMap<>();

	private PackFingerprints() {
	}

	public static void append(final PackResources pack, final PackType type, final ResourceLocation id, final LongArrayList out) {
		source(pack).append(type, id, id.getNamespace() + "/" + id.getPath(), out);
	}

	private static Source source(final PackResources pack) {
		Source source = SOURCES.get(pack);
		if (source == null) {
			source = create(pack);
			SOURCES.put(pack, source);
		}

		return source;
	}

	private static Source create(final PackResources pack) {
		try {
			if (pack instanceof FilePackResources) {
				return ZipSource.of(pack);
			}

			if (pack instanceof PathPackResources) {
				Path root = ((PathPackResourcesAccessor)pack).rpo$getRoot();
				return root.getFileSystem() == FileSystems.getDefault() ? new FolderSource(root) : immutable(pack);
			}

			if (pack instanceof VanillaPackResources) {
				return ((ImmutablePack)pack).rpo$isImmutable() ? immutable(pack) : UNKNOWN_SOURCE;
			}

			if (pack instanceof CompositePackResources) {
				List<Source> layers = new ArrayList<>();
				for (PackResources layer : ((CompositePackResourcesAccessor)pack).rpo$getPackResourcesStack()) {
					layers.add(source(layer));
				}

				return (type, id, path, out) -> {
					for (Source layer : layers) {
						layer.append(type, id, path, out);
					}
				};
			}

			if (pack.getClass().getName().startsWith("net.fabricmc.fabric.impl.resource.") && !FabricLoader.getInstance().isDevelopmentEnvironment()) {
				return immutable(pack);
			}
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.debug("Can't fingerprint pack {}", pack.packId(), e);
		}

		return UNKNOWN_SOURCE;
	}

	private static Source immutable(final PackResources pack) {
		int index = IMMUTABLE_IDS.computeIfAbsent(pack.getClass().getName() + "\n" + pack.packId(), key -> IMMUTABLE_IDS.size());
		return new ImmutableSource(new WeakReference<>(pack), IMMUTABLE_BASE - index);
	}

	@FunctionalInterface
	private interface Source {
		void append(PackType type, ResourceLocation id, String path, LongArrayList out);
	}

	private record ImmutableSource(WeakReference<PackResources> pack, long present) implements Source {
		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			PackResources pack = this.pack.get();
			out.add(pack == null ? UNKNOWN : pack.getResource(type, id) != null ? this.present : ABSENT);
		}
	}

	private static long token(final long crc, final long size) {
		if (crc < 0L || size < 0L || size > 0xFFFFFFFFL) {
			return UNKNOWN;
		}

		return crc << 32 | size;
	}

	private record ZipSource(ZipAccess access, String prefix) implements Source {
		static Source of(final PackResources pack) {
			ZipPack zipPack = (ZipPack)pack;
			ZipAccess access = zipPack.rpo$zipAccess();
			String prefix = zipPack.rpo$prefix();
			if (access == null || prefix == null) {
				ResourcePackOptimizer.LOGGER.debug("Can't read zip of pack {}", pack.packId());
				return UNKNOWN_SOURCE;
			}

			return new ZipSource(access, prefix);
		}

		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			ZipFile zipFile = this.access.rpo$getOrCreateZipFile();
			if (zipFile == null) {
				out.add(ABSENT);
				return;
			}

			String name = type.getDirectory() + "/" + path;
			ZipEntry entry = zipFile.getEntry(this.prefix.isEmpty() ? name : this.prefix + "/" + name);
			if (entry == null) {
				out.add(ABSENT);
			} else if (entry.isDirectory()) {
				out.add(DIRECTORY);
			} else {
				out.add(token(entry.getCrc(), entry.getSize()));
			}
		}
	}

	private static final class FolderSource implements Source {
		private final Path root;
		private final Map<PackType, Map<String, List<Path>>> indexes = new ConcurrentHashMap<>();
		private final Map<Path, Long> tokens = new ConcurrentHashMap<>();

		private FolderSource(final Path root) {
			this.root = root;
		}

		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			Map<String, List<Path>> index = this.indexes.computeIfAbsent(type, this::walk);
			if (index == FAILED_INDEX) {
				out.add(UNKNOWN);
				return;
			}

			List<Path> files = index.get(path.toLowerCase(Locale.ROOT));
			if (files == null) {
				out.add(ABSENT);
				return;
			}

			for (Path file : files) {
				out.add(this.tokens.computeIfAbsent(file, FolderSource::hash));
			}
		}

		private Map<String, List<Path>> walk(final PackType type) {
			Path top = this.root.resolve(type.getDirectory());
			Map<String, List<Path>> index = new HashMap<>();
			if (!Files.isDirectory(top)) {
				return index;
			}

			try (Stream<Path> files = Files.walk(top, FileVisitOption.FOLLOW_LINKS)) {
				files.forEach(file -> {
					if (!file.equals(top)) {
						String key = top.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
						index.computeIfAbsent(key, k -> new ArrayList<>(1)).add(file);
					}
				});
			} catch (IOException | UncheckedIOException | SecurityException e) {
				ResourcePackOptimizer.LOGGER.debug("Couldn't index folder pack {}", this.root, e);
				return FAILED_INDEX;
			}

			return index;
		}

		private static long hash(final Path file) {
			if (Files.isDirectory(file)) {
				return DIRECTORY;
			}

			try (InputStream in = Files.newInputStream(file)) {
				CRC32C crc = new CRC32C();
				byte[] buffer = new byte[16384];
				long size = 0L;
				int read;
				while ((read = in.read(buffer)) > 0) {
					crc.update(buffer, 0, read);
					size += read;
				}

				return token(crc.getValue(), size);
			} catch (IOException | SecurityException e) {
				return UNKNOWN;
			}
		}
	}
}
