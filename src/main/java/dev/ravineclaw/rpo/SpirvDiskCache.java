package dev.ravineclaw.rpo;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

public final class SpirvDiskCache {
	private static final long MAGIC = 0x5250_4F53_5049_5256L;
	private static final int FORMAT = 1;
	private static final long SAVE_DELAY_MILLIS = 2000L;
	private static final int MAX_BYTES = 64 << 20;
	private static final Map<Lookup, List<Entry>> LOADED = new ConcurrentHashMap<>();
	private static final Set<Entry> USED = ConcurrentHashMap.newKeySet();
	private static final CompletableFuture<Void> READ = new CompletableFuture<>();
	private static final AtomicBoolean DIRTY = new AtomicBoolean();
	private static final AtomicInteger SAVE_REQUEST = new AtomicInteger();
	private static final AtomicInteger HITS = new AtomicInteger();
	private static final AtomicLong UNMATCHABLE = new AtomicLong(ThreadLocalRandom.current().nextLong() | 0x4000_0000_0000_0000L);
	private static final Executor SAVER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "RPO shader cache writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static volatile boolean started;
	private static volatile boolean enabled = true;

	private SpirvDiskCache() {
	}

	public record Lookup(String compiler, String name, long first, long second, int length, int type, String defines) {
	}

	public record Include(String id, boolean present, long hash) {
	}

	private record Entry(Lookup lookup, Include[] includes, byte[] spv) {
	}

	public static final class Recorder implements ShaderSource {
		private final ShaderSource delegate;
		private final Map<String, Include> includes = new LinkedHashMap<>();

		public Recorder(final ShaderSource delegate) {
			this.delegate = delegate;
		}

		@Override
		public @Nullable String getShader(final Identifier id, final ShaderType type) {
			return this.delegate.getShader(id, type);
		}

		@Override
		public ShaderSource.@Nullable CachedIncludeSource getInclude(final Identifier id) {
			ShaderSource.CachedIncludeSource include = this.delegate.getInclude(id);
			long hash;
			try {
				hash = hash(include);
			} catch (RuntimeException e) {
				hash = UNMATCHABLE.incrementAndGet();
			}

			synchronized (this.includes) {
				this.includes.putIfAbsent(id.toString(), new Include(id.toString(), include != null, hash));
			}

			return include;
		}

		@Override
		public void close() {
		}

		public Include[] includes() {
			synchronized (this.includes) {
				return this.includes.values().toArray(Include[]::new);
			}
		}
	}

	public static void prefetch() {
		started = true;
		Thread thread = new Thread(SpirvDiskCache::read, "RPO shader cache");
		thread.setDaemon(true);
		thread.start();
	}

	public static Lookup lookup(final String compiler, final String name, final String source, final ShaderType type, final ShaderDefines defines) {
		SpriteCache.Key key = SpriteCache.key(source.getBytes(StandardCharsets.UTF_8));
		StringBuilder encoded = new StringBuilder();
		for (Map.Entry<String, String> value : new TreeMap<>(defines.values()).entrySet()) {
			encoded.append(value.getKey()).append('=').append(value.getValue()).append('\n');
		}

		for (String flag : new TreeSet<>(defines.flags())) {
			encoded.append(flag).append('\n');
		}

		return new Lookup(compiler, name, key.first(), key.second(), key.length(), type.ordinal(), encoded.toString());
	}

	public static byte @Nullable [] find(final Lookup lookup, final ShaderSource source) {
		if (!enabled || !started) {
			return null;
		}

		try {
			READ.get(2L, TimeUnit.SECONDS);
		} catch (Exception e) {
			return null;
		}

		List<Entry> candidates = LOADED.get(lookup);
		if (candidates == null) {
			return null;
		}

		for (Entry entry : candidates) {
			if (includesMatch(entry.includes(), source)) {
				USED.add(entry);
				HITS.incrementAndGet();
				return entry.spv();
			}
		}

		return null;
	}

	public static void store(final Lookup lookup, final Include[] includes, final byte[] spv) {
		if (!enabled) {
			return;
		}

		Entry entry = new Entry(lookup, includes, spv);
		LOADED.computeIfAbsent(lookup, key -> new CopyOnWriteArrayList<>()).add(entry);
		USED.add(entry);
		DIRTY.set(true);
	}

	public static int hits() {
		return HITS.get();
	}

	public static void reloadFinished() {
		if (!enabled || !DIRTY.get()) {
			return;
		}

		int request = SAVE_REQUEST.incrementAndGet();
		CompletableFuture.delayedExecutor(SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS, SAVER).execute(() -> {
			if (request == SAVE_REQUEST.get() && enabled) {
				READ.join();
				save();
			}
		});
	}

	public static void flush() {
		CompletableFuture.runAsync(() -> {
			if (enabled) {
				READ.join();
				save();
			}
		}, SAVER).join();
	}

	public static void clear() {
		enabled = false;
		SAVE_REQUEST.incrementAndGet();
		SAVER.execute(() -> {
			if (!enabled) {
				try {
					Files.deleteIfExists(file());
				} catch (IOException e) {
					ResourcePackOptimizer.LOGGER.debug("Couldn't delete the shader cache", e);
				}
			}
		});
	}

	public static void enable() {
		enabled = true;
		if (!USED.isEmpty()) {
			DIRTY.set(true);
		}
	}

	private static boolean includesMatch(final Include[] includes, final ShaderSource source) {
		for (Include include : includes) {
			Identifier id = Identifier.tryParse(include.id());
			if (id == null) {
				return false;
			}

			ShaderSource.CachedIncludeSource now = source.getInclude(id);
			try {
				if ((now != null) != include.present() || hash(now) != include.hash()) {
					return false;
				}
			} catch (RuntimeException e) {
				return false;
			}
		}

		return true;
	}

	private static long hash(final ShaderSource.@Nullable CachedIncludeSource include) {
		if (include == null) {
			return 0L;
		}

		long result = include.includeResultPtr();
		XXHash64 hasher = XXHashFactory.safeInstance().hash64();
		long hash = PackFingerprints.hashMix(PackFingerprints.hashStart(), hashNative(hasher, result, ShadercIncludeResult.SOURCE_NAME, ShadercIncludeResult.SOURCE_NAME_LENGTH));
		return PackFingerprints.hashMix(hash, hashNative(hasher, result, ShadercIncludeResult.CONTENT, ShadercIncludeResult.CONTENT_LENGTH));
	}

	private static long hashNative(final XXHash64 hasher, final long struct, final int pointerOffset, final int lengthOffset) {
		long address = MemoryUtil.memGetAddress(struct + pointerOffset);
		long length = MemoryUtil.memGetAddress(struct + lengthOffset);
		if (address == 0L || length <= 0L) {
			return length == 0L ? 1L : 2L;
		}

		if (length > Integer.MAX_VALUE) {
			return UNMATCHABLE.incrementAndGet();
		}

		ByteBuffer bytes = MemoryUtil.memByteBuffer(address, (int)length);
		return PackFingerprints.hashMix(hasher.hash(bytes, 0, bytes.remaining(), 0L), length);
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("cache").resolve("resource_pack_optimizer").resolve("shaders.bin");
	}

	private static String version() {
		return SharedConstants.getCurrentVersion().id() + "/" + FORMAT;
	}

	private static void read() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
				return;
			}

			byte[] stored = Files.readAllBytes(file);
			ByteBuffer header = ByteBuffer.wrap(stored);
			if (stored.length < 12 || header.getLong() != MAGIC) {
				return;
			}

			int rawLength = header.getInt();
			if (rawLength < 0 || rawLength > MAX_BYTES) {
				return;
			}

			byte[] raw = new byte[rawLength];
			LZ4Factory.safeInstance().safeDecompressor().decompress(stored, 12, stored.length - 12, raw, 0);
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
				if (!in.readUTF().equals(version())) {
					return;
				}

				int count = in.readInt();
				for (int i = 0; i < count; i++) {
					Lookup lookup = new Lookup(in.readUTF(), in.readUTF(), in.readLong(), in.readLong(), in.readInt(), in.readUnsignedByte(), in.readUTF());
					Include[] includes = new Include[in.readUnsignedShort()];
					for (int j = 0; j < includes.length; j++) {
						includes[j] = new Include(in.readUTF(), in.readBoolean(), in.readLong());
					}

					byte[] spv = new byte[in.readInt()];
					in.readFully(spv);
					LOADED.computeIfAbsent(lookup, key -> new CopyOnWriteArrayList<>()).add(new Entry(lookup, includes, spv));
				}
			}
		} catch (Throwable t) {
			LOADED.clear();
			ResourcePackOptimizer.LOGGER.debug("Couldn't read the shader cache, it will be rebuilt", t);
		} finally {
			READ.complete(null);
		}
	}

	private static synchronized void save() {
		if (!DIRTY.getAndSet(false)) {
			return;
		}

		Path file = file();
		Path temp = file.resolveSibling("shaders.bin.tmp");
		try {
			List<Entry> entries = new ArrayList<>(USED);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeUTF(version());
				out.writeInt(entries.size());
				for (Entry entry : entries) {
					Lookup lookup = entry.lookup();
					out.writeUTF(lookup.compiler());
					out.writeUTF(lookup.name());
					out.writeLong(lookup.first());
					out.writeLong(lookup.second());
					out.writeInt(lookup.length());
					out.writeByte(lookup.type());
					out.writeUTF(lookup.defines());
					out.writeShort(entry.includes().length);
					for (Include include : entry.includes()) {
						out.writeUTF(include.id());
						out.writeBoolean(include.present());
						out.writeLong(include.hash());
					}

					out.writeInt(entry.spv().length);
					out.write(entry.spv());
				}
			}

			byte[] raw = bytes.toByteArray();
			if (raw.length > MAX_BYTES) {
				return;
			}

			byte[] compressed = LZ4Factory.safeInstance().fastCompressor().compress(raw);
			ByteBuffer header = ByteBuffer.allocate(12);
			header.putLong(MAGIC);
			header.putInt(raw.length);
			Files.createDirectories(file.getParent());
			byte[] whole = Arrays.copyOf(header.array(), 12 + compressed.length);
			System.arraycopy(compressed, 0, whole, 12, compressed.length);
			Files.write(temp, whole);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (ReloadTimeline.ENABLED) {
				ResourcePackOptimizer.LOGGER.info("[selftest] saved {} compiled shaders ({} KiB on disk) to the shader cache", entries.size(), whole.length >> 10);
			}
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.debug("Couldn't save the shader cache", t);
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
			}
		}
	}
}
