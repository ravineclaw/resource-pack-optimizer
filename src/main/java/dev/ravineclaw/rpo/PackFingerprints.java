package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.CompositePackResourcesAccessor;
import dev.ravineclaw.rpo.mixin.PathPackResourcesAccessor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.jetbrains.annotations.Nullable;

public final class PackFingerprints {
	public static final long ABSENT = -1L;
	public static final long UNKNOWN = Long.MIN_VALUE;
	private static final long DIRECTORY = -2L;
	private static final long IMMUTABLE_BASE = -16L;

	private static final Map<PackResources, Source> SOURCES = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Set<PackResources> PER_RELOAD = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Map<String, Integer> IMMUTABLE_IDS = new ConcurrentHashMap<>();
	private static final Source UNKNOWN_SOURCE = new Source() {
		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			out.add(UNKNOWN);
		}

		@Override
		public long version(final PackType type) {
			return UNKNOWN;
		}
	};
	private static final Map<Path, FileToken> FILE_TOKENS = new ConcurrentHashMap<>();
	private static final long RACY_MILLIS = 3000L;
	private static final Map<String, List<Path>> FAILED_INDEX = new HashMap<>();

	private PackFingerprints() {
	}

	public static StackSources sources(final List<PackResources> packs, final PackType type, final List<String> filters) {
		Source[] sources = new Source[packs.size()];
		for (int i = 0; i < sources.length; i++) {
			sources[i] = source(packs.get(i));
		}

		return new StackSources(packs, type, filters, sources);
	}

	public static final class StackSources {
		private final List<PackResources> packs;
		private final PackType type;
		private final List<String> filters;
		private final Source[] sources;
		private final Map<String, int[]> byNamespace = new ConcurrentHashMap<>();
		private volatile @Nullable Set<String> @Nullable [] namespaces;

		private StackSources(final List<PackResources> packs, final PackType type, final List<String> filters, final Source[] sources) {
			this.packs = packs;
			this.type = type;
			this.filters = filters;
			this.sources = sources;
		}

		public int[] candidates(final String namespace) {
			return this.byNamespace.computeIfAbsent(namespace, this::findCandidates);
		}

		public boolean filtered(final int index) {
			return !this.filters.get(index).isEmpty();
		}

		public void append(final int index, final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			this.sources[index].append(type, id, path, out);
		}

		private int[] findCandidates(final String namespace) {
			Set<String>[] namespaces = this.namespaces();
			int[] found = new int[namespaces.length];
			int count = 0;
			for (int i = 0; i < namespaces.length; i++) {
				if (namespaces[i] == null || namespaces[i].contains(namespace) || this.filtered(i)) {
					found[count++] = i;
				}
			}

			return Arrays.copyOf(found, count);
		}

		@SuppressWarnings("unchecked")
		private Set<String>[] namespaces() {
			Set<String>[] namespaces = this.namespaces;
			if (namespaces == null) {
				namespaces = new Set[this.packs.size()];
				for (int i = 0; i < namespaces.length; i++) {
					try {
						namespaces[i] = Set.copyOf(this.packs.get(i).getNamespaces(this.type));
					} catch (RuntimeException e) {
						namespaces[i] = null;
					}
				}

				this.namespaces = namespaces;
			}

			return namespaces;
		}
	}

	public static long version(final PackResources pack, final PackType type) {
		try {
			return source(pack).version(type);
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.debug("Can't version pack {}", pack.packId(), e);
			return UNKNOWN;
		}
	}

	public static long hashStart() {
		return 0x6A09E667F3BCC909L;
	}

	public static long hashMix(final long hash, final long value) {
		return fmix(hash * 0x9E3779B97F4A7C15L + fmix(value ^ 0xC2B2AE3D27D4EB4FL));
	}

	public static long hashString(final String value) {
		long hash = 0xCBF29CE484222325L;
		for (int i = 0; i < value.length(); i++) {
			hash = (hash ^ value.charAt(i)) * 0x100000001B3L;
		}

		return fmix(hash ^ value.length());
	}

	private static long fmix(long k) {
		k ^= k >>> 33;
		k *= 0xFF51AFD7ED558CCDL;
		k ^= k >>> 33;
		k *= 0xC4CEB9FE1A85EC53L;
		k ^= k >>> 33;
		return k;
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

				return composite(layers);
			}

			if (pack.getClass().getName().startsWith("net.fabricmc.fabric.impl.resource.")) {
				return FabricLoader.getInstance().isDevelopmentEnvironment() ? modFolders(pack) : immutable(pack);
			}
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.debug("Can't fingerprint pack {}", pack.packId(), e);
		}

		return UNKNOWN_SOURCE;
	}

	public static void newReload() {
		synchronized (PER_RELOAD) {
			for (PackResources pack : PER_RELOAD) {
				SOURCES.remove(pack);
			}

			PER_RELOAD.clear();
		}
	}

	private static Source modFolders(final PackResources pack) {
		List<?> paths;
		try {
			Field field = pack.getClass().getDeclaredField("basePaths");
			field.setAccessible(true);
			if (!(field.get(pack) instanceof List<?> list) || list.isEmpty()) {
				return UNKNOWN_SOURCE;
			}

			paths = list;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return UNKNOWN_SOURCE;
		}

		List<Source> layers = new ArrayList<>();
		boolean archived = false;
		for (Object path : paths) {
			if (!(path instanceof Path root)) {
				return UNKNOWN_SOURCE;
			}

			if (root.getFileSystem() == FileSystems.getDefault()) {
				layers.add(new FolderSource(root));
			} else {
				archived = true;
			}
		}

		if (layers.isEmpty()) {
			return immutable(pack);
		}

		if (archived) {
			layers.add(immutable(pack));
		}

		synchronized (PER_RELOAD) {
			PER_RELOAD.add(pack);
		}

		return composite(layers);
	}

	private static Source composite(final List<Source> layers) {
		return new Source() {
			@Override
			public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
				for (Source layer : layers) {
					layer.append(type, id, path, out);
				}
			}

			@Override
			public long version(final PackType type) {
				long hash = hashStart();
				for (Source layer : layers) {
					long version = layer.version(type);
					if (version == UNKNOWN) {
						return UNKNOWN;
					}

					hash = hashMix(hash, version);
				}

				return hash;
			}
		};
	}

	private static Source immutable(final PackResources pack) {
		int index = IMMUTABLE_IDS.computeIfAbsent(pack.getClass().getName() + "\n" + pack.packId(), key -> IMMUTABLE_IDS.size());
		return new ImmutableSource(new WeakReference<>(pack), IMMUTABLE_BASE - index);
	}

	private interface Source {
		void append(PackType type, ResourceLocation id, String path, LongArrayList out);

		long version(PackType type);
	}

	private record FileToken(long size, long modified, long created, long hashedAt, long token) {
	}

	private record ImmutableSource(WeakReference<PackResources> pack, long present) implements Source {
		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			PackResources pack = this.pack.get();
			out.add(pack == null ? UNKNOWN : pack.getResource(type, id) != null ? this.present : ABSENT);
		}

		@Override
		public long version(final PackType type) {
			return this.pack.get() == null ? UNKNOWN : hashMix(this.present, type.ordinal());
		}
	}

	private static long token(final long crc, final long size) {
		if (crc < 0L || size < 0L || size > 0xFFFFFFFFL) {
			return UNKNOWN;
		}

		return crc << 32 | size;
	}

	private static final class ZipSource implements Source {
		private final Object access;
		private final String prefix;
		private volatile @Nullable ZipFile indexedFile;
		private volatile @Nullable ZipIndex index;
		private static volatile Field accessField;
		private static volatile Field prefixField;
		private static volatile Method getOrCreate;

		private ZipSource(final Object access, final String prefix) {
			this.access = access;
			this.prefix = prefix;
		}

		private ZipIndex index(final ZipFile zipFile) {
			ZipIndex index = this.index;
			if (index == null || this.indexedFile != zipFile) {
				index = ZipIndex.of(zipFile);
				this.index = index;
				this.indexedFile = zipFile;
			}

			return index;
		}

		static Source of(final PackResources pack) {
			try {
				if (getOrCreate == null) {
					Field access = null;
					Field prefix = null;
					for (Field field : FilePackResources.class.getDeclaredFields()) {
						if (Modifier.isStatic(field.getModifiers())) {
							continue;
						}

						if (field.getType() == String.class) {
							if (prefix != null) {
								throw new NoSuchFieldException("More than one prefix field");
							}

							prefix = field;
						} else if (field.getType().getDeclaringClass() == FilePackResources.class) {
							if (access != null) {
								throw new NoSuchFieldException("More than one zip access field");
							}

							access = field;
						}
					}

					if (access == null || prefix == null) {
						throw new NoSuchFieldException("Zip access or prefix field not found");
					}

					Method method = null;
					for (Method candidate : access.getType().getDeclaredMethods()) {
						if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getParameterCount() == 0 && candidate.getReturnType() == ZipFile.class) {
							if (method != null) {
								throw new NoSuchMethodException("More than one zip file getter");
							}

							method = candidate;
						}
					}

					if (method == null) {
						throw new NoSuchMethodException("Zip file getter not found");
					}

					access.setAccessible(true);
					prefix.setAccessible(true);
					method.setAccessible(true);
					accessField = access;
					prefixField = prefix;
					getOrCreate = method;
				}

				return new ZipSource(accessField.get(pack), (String)prefixField.get(pack));
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.debug("Can't read zip of pack {}", pack.packId(), e);
				return UNKNOWN_SOURCE;
			}
		}

		@Override
		public long version(final PackType type) {
			ZipFile zipFile;
			try {
				zipFile = (ZipFile)getOrCreate.invoke(this.access);
			} catch (ReflectiveOperationException e) {
				return UNKNOWN;
			}

			if (zipFile == null) {
				return hashMix(hashStart(), ABSENT);
			}

			return hashMix(hashString(this.prefix), this.index(zipFile).contentHash());
		}

		@Override
		public void append(final PackType type, final ResourceLocation id, final String path, final LongArrayList out) {
			ZipFile zipFile;
			try {
				zipFile = (ZipFile)getOrCreate.invoke(this.access);
			} catch (ReflectiveOperationException e) {
				out.add(UNKNOWN);
				return;
			}

			if (zipFile == null) {
				out.add(ABSENT);
				return;
			}

			String name = type.getDirectory() + "/" + path;
			ZipEntry entry = this.index(zipFile).entry(zipFile, this.prefix.isEmpty() ? name : this.prefix + "/" + name);
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
				out.add(this.token(file));
			}
		}

		@Override
		public long version(final PackType type) {
			Map<String, List<Path>> index = this.indexes.computeIfAbsent(type, this::walk);
			if (index == FAILED_INDEX) {
				return UNKNOWN;
			}

			String[] keys = index.keySet().toArray(String[]::new);
			Arrays.sort(keys);
			long hash = hashStart();
			for (String key : keys) {
				hash = hashMix(hash, hashString(key));
				for (Path file : index.get(key)) {
					long token = this.token(file);
					if (token == UNKNOWN) {
						return UNKNOWN;
					}

					hash = hashMix(hash, hashString(file.getFileName().toString()));
					hash = hashMix(hash, token);
				}
			}

			return hash;
		}

		private long token(final Path file) {
			return this.tokens.computeIfAbsent(file, FolderSource::cachedHash);
		}

		private static long cachedHash(final Path file) {
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(file, BasicFileAttributes.class);
			} catch (IOException | SecurityException e) {
				FILE_TOKENS.remove(file);
				return UNKNOWN;
			}

			if (attributes.isDirectory()) {
				return DIRECTORY;
			}

			long size = attributes.size();
			long modified = attributes.lastModifiedTime().toMillis();
			long created = attributes.creationTime().toMillis();
			FileToken known = FILE_TOKENS.get(file);
			if (known != null
				&& known.size() == size
				&& known.modified() == modified
				&& known.created() == created
				&& known.hashedAt() - modified > RACY_MILLIS) {
				return known.token();
			}

			long hashedAt = System.currentTimeMillis();
			long token = hash(file);
			if (token == UNKNOWN) {
				FILE_TOKENS.remove(file);
			} else {
				FILE_TOKENS.put(file, new FileToken(size, modified, created, hashedAt, token));
			}

			return token;
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

				return PackFingerprints.token(crc.getValue(), size);
			} catch (IOException | SecurityException e) {
				return UNKNOWN;
			}
		}
	}
}
