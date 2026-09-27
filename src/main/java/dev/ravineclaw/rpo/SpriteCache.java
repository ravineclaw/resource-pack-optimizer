package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class SpriteCache {
	public static final String[] CLASSES = {
		"net.minecraft.client.renderer.texture.SpriteLoader",
		"net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader",
		"net.minecraft.client.renderer.texture.SpriteContents",
		"net.minecraft.client.renderer.texture.MipmapGenerator",
		"com.mojang.blaze3d.platform.NativeImage",
		"com.mojang.blaze3d.platform.TextureUtil"
	};
	private static final long MIN_BUDGET = 128L << 20;
	private static final long MAX_BUDGET = 512L << 20;
	private static final long BUDGET = Math.clamp(Runtime.getRuntime().maxMemory() / 8L, MIN_BUDGET, MAX_BUDGET);
	private static final ThreadLocal<Pending> LOADING = new ThreadLocal<>();
	private static final Map<Key, Entry> ENTRIES = new ConcurrentHashMap<>();
	private static final AtomicLong BYTES = new AtomicLong();
	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static final AtomicInteger HITS = new AtomicInteger();
	private static final AtomicInteger MISSES = new AtomicInteger();
	private static final AtomicInteger MIP_HITS = new AtomicInteger();
	private static final AtomicInteger MIP_MISSES = new AtomicInteger();
	private static volatile @Nullable XXHash64 hash;

	private SpriteCache() {
	}

	public record Key(long first, long second, int length) {
	}

	public record MipKey(boolean item, MipmapStrategy strategy, float alphaCutoffBias) {
	}

	public static final class Entry {
		private final Key key;
		private final NativeImage original;
		private final Map<MipKey, NativeImage[]> chains = new ConcurrentHashMap<>();
		private volatile int lastUsed;
		private boolean closed;

		Entry(final Key key, final NativeImage original, final int generation) {
			this.key = key;
			this.original = original;
			this.lastUsed = generation;
		}

		public Key key() {
			return this.key;
		}

		public NativeImage original() {
			return this.original;
		}

		public int lastUsed() {
			return this.lastUsed;
		}

		public synchronized long withImages(final ImageVisitor visitor) throws IOException {
			if (this.closed) {
				return -1L;
			}

			return visitor.visit(this.original, Map.copyOf(this.chains));
		}

		private synchronized long close() {
			if (this.closed) {
				return 0L;
			}

			this.closed = true;
			long freed = size(this.original);
			this.original.close();
			for (NativeImage[] chain : this.chains.values()) {
				freed += closeChain(chain);
			}

			this.chains.clear();
			return freed;
		}
	}

	public interface ImageVisitor {
		long visit(NativeImage original, Map<MipKey, NativeImage[]> chains) throws IOException;
	}

	private static final class Pending {
		private @Nullable Entry entry;
		private @Nullable NativeImage image;
	}

	public static SpriteResourceLoader wrap(final SpriteResourceLoader loader) {
		if (!RpoSettings.active() || !ReuseGuard.untouched("decoded sprites", CLASSES)) {
			return loader;
		}

		return (location, resource) -> load(loader, location, resource);
	}

	private static @Nullable SpriteContents load(final SpriteResourceLoader loader, final Identifier location, final Resource resource) {
		Pending pending = new Pending();
		LOADING.set(pending);
		SpriteContents contents;
		try {
			contents = loader.loadSprite(location, resource);
		} finally {
			LOADING.remove();
		}

		if (contents != null && pending.entry != null && ((CachedSprite)contents).rpo$originalImage() == pending.image) {
			((CachedSprite)contents).rpo$setCacheEntry(pending.entry);
		}

		return contents;
	}

	public static @Nullable NativeImage read(final InputStream stream) throws IOException {
		Pending pending = LOADING.get();
		if (pending == null) {
			return null;
		}

		LOADING.remove();
		byte[] bytes;
		try (stream) {
			bytes = stream.readAllBytes();
		}

		Entry entry;
		NativeImage image;
		try {
			Key key = key(bytes);
			entry = ENTRIES.get(key);
			image = entry != null ? copyOriginal(entry) : null;
			if (image == null) {
				SpriteDiskCache.awaitIfStored(key);
				entry = ENTRIES.get(key);
				image = entry != null ? copyOriginal(entry) : null;
			}

			if (image != null) {
				HITS.incrementAndGet();
			} else {
				MISSES.incrementAndGet();
				image = NativeImage.read(bytes);
				entry = store(key, image);
			}
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			ResourcePackOptimizer.LOGGER.debug("Sprite cache failed, decoding normally", e);
			return NativeImage.read(bytes);
		}

		pending.entry = entry;
		pending.image = image;
		return image;
	}

	private static @Nullable NativeImage copyOriginal(final Entry entry) {
		synchronized (entry) {
			if (entry.closed) {
				return null;
			}

			entry.lastUsed = GENERATION.get();
			return copy(entry.original);
		}
	}

	private static @Nullable Entry store(final Key key, final NativeImage image) {
		if (image.format() != NativeImage.Format.RGBA || !reserve(size(image))) {
			return null;
		}

		Entry entry = new Entry(key, copy(image), GENERATION.get());
		Entry previous = ENTRIES.putIfAbsent(key, entry);
		if (previous != null) {
			BYTES.addAndGet(-entry.close());
			return previous;
		}

		SpriteDiskCache.markDirty();
		return entry;
	}

	public static boolean adopt(final Key key, final NativeImage original, final Map<MipKey, NativeImage[]> chains) {
		long size = size(original);
		for (NativeImage[] chain : chains.values()) {
			size += chainSize(chain);
		}

		if (ENTRIES.containsKey(key) || !reserve(size)) {
			return false;
		}

		Entry entry = new Entry(key, original, GENERATION.get() - 1);
		entry.chains.putAll(chains);
		if (ENTRIES.putIfAbsent(key, entry) != null) {
			BYTES.addAndGet(-size);
			return false;
		}

		return true;
	}

	public static List<Entry> snapshot() {
		return new ArrayList<>(ENTRIES.values());
	}

	public static boolean serveMips(
		final Entry entry, final MipKey mipKey, final int mipLevel, final NativeImage original, final NativeImage[] result
	) {
		synchronized (entry) {
			NativeImage[] chain = entry.chains.get(mipKey);
			if (entry.closed || chain == null || chain.length <= mipLevel || !sameShape(chain[0], original)) {
				MIP_MISSES.incrementAndGet();
				return false;
			}

			MemoryUtil.memCopy(chain[0].getPointer(), original.getPointer(), size(original));
			result[0] = original;
			for (int level = 1; level <= mipLevel; level++) {
				result[level] = copy(chain[level]);
			}

			entry.lastUsed = GENERATION.get();
			MIP_HITS.incrementAndGet();
			return true;
		}
	}

	public static void storeMips(final Entry entry, final MipKey mipKey, final NativeImage[] mips) {
		NativeImage[] known = entry.chains.get(mipKey);
		if (known != null && known.length >= mips.length) {
			return;
		}

		long size = 0L;
		for (NativeImage mip : mips) {
			if (mip == null || mip.format() != NativeImage.Format.RGBA) {
				return;
			}

			size += size(mip);
		}

		if (!reserve(size)) {
			return;
		}

		NativeImage[] chain = new NativeImage[mips.length];
		for (int level = 0; level < mips.length; level++) {
			chain[level] = copy(mips[level]);
		}

		long release = size;
		synchronized (entry) {
			NativeImage[] current = entry.chains.get(mipKey);
			if (!entry.closed && (current == null || current.length < chain.length)) {
				entry.chains.put(mipKey, chain);
				SpriteDiskCache.markDirty();
				chain = current;
				release = current == null ? 0L : chainSize(current);
			}
		}

		if (chain != null) {
			closeChain(chain);
		}

		BYTES.addAndGet(-release);
	}

	public static void newGeneration() {
		int generation = GENERATION.incrementAndGet();
		if (ResourcePackOptimizer.LOGGER.isDebugEnabled() || ReloadTimeline.ENABLED) {
			ResourcePackOptimizer.LOGGER.info(
				"[selftest] sprite cache before reload {}: {} entries, {} MiB of {} MiB, decode hits {} / misses {}, mipmap hits {} / misses {}",
				generation, ENTRIES.size(), BYTES.get() >> 20, BUDGET >> 20, HITS.getAndSet(0), MISSES.getAndSet(0), MIP_HITS.getAndSet(0), MIP_MISSES.getAndSet(0)
			);
		}
	}

	public static void clear() {
		for (Entry entry : ENTRIES.values()) {
			if (ENTRIES.remove(entry.key, entry)) {
				BYTES.addAndGet(-entry.close());
			}
		}
	}

	private static boolean reserve(final long size) {
		if (size > BUDGET / 4L) {
			return false;
		}

		if (BYTES.addAndGet(size) <= BUDGET) {
			return true;
		}

		evictOlderThan(GENERATION.get(), size);
		if (BYTES.get() <= BUDGET) {
			return true;
		}

		BYTES.addAndGet(-size);
		return false;
	}

	private static synchronized void evictOlderThan(final int generation, final long wanted) {
		List<Entry> old = new ArrayList<>();
		for (Entry entry : ENTRIES.values()) {
			if (entry.lastUsed < generation) {
				old.add(entry);
			}
		}

		old.sort(Comparator.comparingInt(entry -> entry.lastUsed));
		for (Entry entry : old) {
			if (BYTES.get() <= BUDGET - wanted / 2L) {
				return;
			}

			if (ENTRIES.remove(entry.key, entry)) {
				BYTES.addAndGet(-entry.close());
			}
		}
	}

	private static boolean sameShape(final NativeImage a, final NativeImage b) {
		return a.format() == b.format() && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
	}

	public static long size(final NativeImage image) {
		return (long)image.getWidth() * image.getHeight() * image.format().components();
	}

	private static NativeImage copy(final NativeImage source) {
		NativeImage copy = new NativeImage(source.format(), source.getWidth(), source.getHeight(), false);
		MemoryUtil.memCopy(source.getPointer(), copy.getPointer(), size(source));
		return copy;
	}

	private static long chainSize(final NativeImage[] chain) {
		long size = 0L;
		for (NativeImage image : chain) {
			size += size(image);
		}

		return size;
	}

	private static long closeChain(final NativeImage[] chain) {
		long size = 0L;
		for (NativeImage image : chain) {
			size += size(image);
			image.close();
		}

		return size;
	}

	public static Key key(final byte[] bytes) {
		XXHash64 hash = SpriteCache.hash;
		if (hash == null) {
			hash = XXHashFactory.safeInstance().hash64();
			SpriteCache.hash = hash;
		}

		return new Key(hash.hash(bytes, 0, bytes.length, 0x5250_4F31L), hash.hash(bytes, 0, bytes.length, 0x9E37_79B9_7F4A_7C15L), bytes.length);
	}
}
