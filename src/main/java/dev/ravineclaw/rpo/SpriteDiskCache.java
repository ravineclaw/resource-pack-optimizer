package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class SpriteDiskCache {
	private static final long MAGIC = 0x5250_4F53_5052_5431L;
	private static final int FORMAT = 1;
	private static final long DISK_BUDGET = 256L << 20;
	private static final long SAVE_DELAY_MILLIS = 2000L;
	private static final int PREFETCH_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
	private static final Map<SpriteCache.Key, Stored> STORED = new ConcurrentHashMap<>();
	private static final AtomicBoolean DIRTY = new AtomicBoolean();
	private static final AtomicInteger SAVE_REQUEST = new AtomicInteger();
	private static final CompletableFuture<Void> PREFETCHED = new CompletableFuture<>();
	private static final Executor SAVER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "RPO sprite cache writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static volatile boolean enabled = true;
	private static volatile @Nullable FileChannel channel;

	private SpriteDiskCache() {
	}

	private record Image(int width, int height, long offset, int compressed, long checksum) {
	}

	private record Chain(SpriteCache.MipKey key, Image[] levels) {
	}

	private record Written(SpriteCache.Key key, Image original, List<Chain> chains) {
	}

	private static final class Stored {
		private final SpriteCache.Key key;
		private final Image original;
		private final Chain[] chains;
		private final AtomicBoolean claimed = new AtomicBoolean();
		private final CompletableFuture<Void> loaded = new CompletableFuture<>();

		private Stored(final SpriteCache.Key key, final Image original, final Chain[] chains) {
			this.key = key;
			this.original = original;
			this.chains = chains;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("cache").resolve("resource_pack_optimizer").resolve("sprites.bin");
	}

	private static String version() {
		return SharedConstants.getCurrentVersion().id() + "/" + FORMAT;
	}

	public static void prefetch() {
		Thread thread = new Thread(SpriteDiskCache::runPrefetch, "RPO sprite cache");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
	}

	private static void runPrefetch() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) {
				return;
			}

			FileChannel opened = FileChannel.open(file, StandardOpenOption.READ);
			try {
				if (!readIndex(opened)) {
					STORED.clear();
					opened.close();
					return;
				}
			} catch (IOException | RuntimeException e) {
				STORED.clear();
				opened.close();
				throw e;
			}

			if (!ReuseGuard.untouched("decoded sprites", SpriteCache.CLASSES)) {
				STORED.clear();
				opened.close();
				return;
			}

			channel = opened;
			List<Stored> all = new ArrayList<>(STORED.values());
			AtomicInteger next = new AtomicInteger();
			List<Thread> workers = new ArrayList<>();
			for (int i = 0; i < PREFETCH_THREADS; i++) {
				Thread worker = new Thread(() -> {
					int index;
					while (enabled && (index = next.getAndIncrement()) < all.size()) {
						load(all.get(index));
					}
				}, "RPO sprite cache " + i);
				worker.setDaemon(true);
				worker.setPriority(Thread.NORM_PRIORITY - 1);
				worker.start();
				workers.add(worker);
			}

			long start = System.nanoTime();
			for (Thread worker : workers) {
				worker.join();
			}

			if (ReloadTimeline.ENABLED) {
				ResourcePackOptimizer.LOGGER.info("[selftest] sprite cache prefetch: {} stored sprites read in {} ms", all.size(), (System.nanoTime() - start) / 1_000_000L);
			}
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.info("Couldn't read the sprite cache, it will be rebuilt: {}", t.toString());
		} finally {
			closeChannel();
			PREFETCHED.complete(null);
		}
	}

	private static boolean readIndex(final FileChannel opened) throws IOException {
		long size = opened.size();
		if (size < 32L) {
			return false;
		}

		ByteBuffer footer = ByteBuffer.allocate(16);
		readFully(opened, footer, size - 16L);
		footer.flip();
		long indexOffset = footer.getLong();
		if (footer.getLong() != MAGIC || indexOffset < 0L || indexOffset > size - 16L) {
			return false;
		}

		ByteBuffer indexBytes = ByteBuffer.allocate(Math.toIntExact(size - 16L - indexOffset));
		readFully(opened, indexBytes, indexOffset);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(indexBytes.array()))) {
			if (in.readLong() != MAGIC || !in.readUTF().equals(version())) {
				return false;
			}

			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				SpriteCache.Key key = new SpriteCache.Key(in.readLong(), in.readLong(), in.readInt());
				Image original = readImage(in);
				Chain[] chains = new Chain[in.readUnsignedByte()];
				for (int c = 0; c < chains.length; c++) {
					boolean item = in.readBoolean();
					MipmapStrategy strategy = MipmapStrategy.valueOf(in.readUTF());
					float bias = in.readFloat();
					Image[] levels = new Image[in.readUnsignedByte()];
					for (int l = 0; l < levels.length; l++) {
						levels[l] = readImage(in);
					}

					chains[c] = new Chain(new SpriteCache.MipKey(item, strategy, bias), levels);
				}

				STORED.put(key, new Stored(key, original, chains));
			}
		} catch (EOFException | IllegalArgumentException e) {
			return false;
		}

		return true;
	}

	private static Image readImage(final DataInputStream in) throws IOException {
		return new Image(in.readInt(), in.readInt(), in.readLong(), in.readInt(), in.readLong());
	}

	private static void writeImage(final DataOutputStream out, final Image image) throws IOException {
		out.writeInt(image.width());
		out.writeInt(image.height());
		out.writeLong(image.offset());
		out.writeInt(image.compressed());
		out.writeLong(image.checksum());
	}

	public static void awaitIfStored(final SpriteCache.Key key) {
		Stored stored = STORED.get(key);
		if (stored == null || !enabled) {
			return;
		}

		if (!load(stored)) {
			try {
				stored.loaded.join();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private static boolean load(final Stored stored) {
		if (!stored.claimed.compareAndSet(false, true)) {
			return false;
		}

		List<NativeImage> made = new ArrayList<>();
		try {
			FileChannel opened = channel;
			if (opened == null || !enabled) {
				return true;
			}

			NativeImage original = readImage(opened, stored.original, made);
			Map<SpriteCache.MipKey, NativeImage[]> chains = new HashMap<>();
			for (Chain chain : stored.chains) {
				NativeImage[] levels = new NativeImage[chain.levels().length];
				for (int l = 0; l < levels.length; l++) {
					levels[l] = readImage(opened, chain.levels()[l], made);
				}

				chains.put(chain.key(), levels);
			}

			if (SpriteCache.adopt(stored.key, original, chains)) {
				made.clear();
			}
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.debug("Couldn't read a cached sprite", t);
		} finally {
			for (NativeImage image : made) {
				image.close();
			}

			STORED.remove(stored.key, stored);
			stored.loaded.complete(null);
		}

		return true;
	}

	private static NativeImage readImage(final FileChannel opened, final Image image, final List<NativeImage> made) throws IOException {
		NativeImage result = new NativeImage(NativeImage.Format.RGBA, image.width(), image.height(), false);
		made.add(result);
		int raw = Math.toIntExact(SpriteCache.size(result));
		ByteBuffer compressed = ByteBuffer.allocate(image.compressed());
		readFully(opened, compressed, image.offset());
		byte[] pixels = new byte[raw];
		int read = decompressor().decompress(compressed.array(), 0, image.compressed(), pixels, 0, raw);
		if (read != raw || hasher().hash(pixels, 0, raw, 0L) != image.checksum()) {
			throw new IOException("Cached sprite is damaged");
		}

		MemoryUtil.memByteBuffer(result.getPointer(), raw).put(pixels);
		return result;
	}

	private static void readFully(final FileChannel opened, final ByteBuffer buffer, final long position) throws IOException {
		long at = position;
		while (buffer.hasRemaining()) {
			int read = opened.read(buffer, at);
			if (read < 0) {
				throw new EOFException();
			}

			at += read;
		}
	}

	private static void closeChannel() {
		FileChannel opened = channel;
		channel = null;
		if (opened != null) {
			try {
				opened.close();
			} catch (IOException ignored) {
			}
		}
	}

	public static void markDirty() {
		DIRTY.set(true);
	}

	public static void reloadFinished() {
		if (!enabled || !RpoSettings.active() || !DIRTY.get()) {
			return;
		}

		int request = SAVE_REQUEST.incrementAndGet();
		CompletableFuture.delayedExecutor(SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS, SAVER).execute(() -> {
			if (request == SAVE_REQUEST.get() && enabled && RpoSettings.active()) {
				PREFETCHED.join();
				save();
			}
		});
	}

	public static void reloadStarted() {
		SAVE_REQUEST.incrementAndGet();
	}

	private static synchronized void save() {
		if (!DIRTY.getAndSet(false)) {
			return;
		}

		Path file = file();
		Path temp = file.resolveSibling("sprites.bin.tmp");
		try {
			Files.createDirectories(file.getParent());
			List<SpriteCache.Entry> entries = SpriteCache.snapshot();
			entries.sort(Comparator.comparingInt(SpriteCache.Entry::lastUsed).reversed());
			ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
			int written = 0;
			long total = 0L;
			try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				DataOutputStream index = new DataOutputStream(indexBytes)) {
				long position = 0L;
				List<Written> pending = new ArrayList<>();
				for (SpriteCache.Entry entry : entries) {
					if (total >= DISK_BUDGET) {
						break;
					}

					List<Chain> chains = new ArrayList<>();
					Image[] original = new Image[1];
					long[] at = {position};
					long size = entry.withImages((image, mips) -> {
						original[0] = writeCompressed(out, image, at);
						long bytes = SpriteCache.size(image);
						for (Map.Entry<SpriteCache.MipKey, NativeImage[]> chain : mips.entrySet()) {
							Image[] levels = new Image[chain.getValue().length];
							for (int l = 0; l < levels.length; l++) {
								levels[l] = writeCompressed(out, chain.getValue()[l], at);
								bytes += SpriteCache.size(chain.getValue()[l]);
							}

							chains.add(new Chain(chain.getKey(), levels));
						}

						return bytes;
					});
					position = at[0];
					if (size >= 0L) {
						total += size;
						pending.add(new Written(entry.key(), original[0], chains));
					}
				}

				index.writeLong(MAGIC);
				index.writeUTF(version());
				index.writeInt(pending.size());
				for (Written record : pending) {
					index.writeLong(record.key().first());
					index.writeLong(record.key().second());
					index.writeInt(record.key().length());
					writeImage(index, record.original());
					index.writeByte(record.chains().size());
					for (Chain chain : record.chains()) {
						index.writeBoolean(chain.key().item());
						index.writeUTF(chain.key().strategy().name());
						index.writeFloat(chain.key().alphaCutoffBias());
						index.writeByte(chain.levels().length);
						for (Image level : chain.levels()) {
							writeImage(index, level);
						}
					}
				}

				index.flush();
				writeFully(out, ByteBuffer.wrap(indexBytes.toByteArray()));
				ByteBuffer footer = ByteBuffer.allocate(16);
				footer.putLong(position);
				footer.putLong(MAGIC);
				footer.flip();
				writeFully(out, footer);
				out.force(false);
				written = pending.size();
			}

			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (ReloadTimeline.ENABLED) {
				ResourcePackOptimizer.LOGGER.info("[selftest] saved {} decoded sprites ({} MiB, {} MiB on disk) to the sprite cache", written, total >> 20, Files.size(file) >> 20);
			}
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.debug("Couldn't save the sprite cache", t);
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
			}
		}
	}

	private static Image writeCompressed(final FileChannel out, final NativeImage image, final long[] position) throws IOException {
		int raw = Math.toIntExact(SpriteCache.size(image));
		byte[] pixels = new byte[raw];
		MemoryUtil.memByteBuffer(image.getPointer(), raw).get(pixels);
		LZ4Compressor compressor = compressor();
		byte[] compressed = new byte[compressor.maxCompressedLength(raw)];
		int length = compressor.compress(pixels, 0, raw, compressed, 0, compressed.length);
		Image written = new Image(image.getWidth(), image.getHeight(), position[0], length, hasher().hash(pixels, 0, raw, 0L));
		writeFully(out, ByteBuffer.wrap(compressed, 0, length));
		position[0] += length;
		return written;
	}

	private static void writeFully(final FileChannel out, final ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			out.write(buffer);
		}
	}

	public static void deleteLater() {
		enabled = false;
		STORED.clear();
		SAVE_REQUEST.incrementAndGet();
		SAVER.execute(() -> {
			PREFETCHED.join();
			if (!enabled) {
				try {
					Files.deleteIfExists(file());
				} catch (IOException e) {
					ResourcePackOptimizer.LOGGER.debug("Couldn't delete the sprite cache", e);
				}
			}
		});
	}

	public static void enable() {
		enabled = true;
	}

	private static LZ4Compressor compressor() {
		return LZ4Factory.safeInstance().fastCompressor();
	}

	private static LZ4SafeDecompressor decompressor() {
		return LZ4Factory.safeInstance().safeDecompressor();
	}

	private static XXHash64 hasher() {
		return XXHashFactory.safeInstance().hash64();
	}
}
