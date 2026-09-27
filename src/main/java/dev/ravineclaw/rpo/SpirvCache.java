package dev.ravineclaw.rpo;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryUtil;

public final class SpirvCache {
	private static final String[] CLASSES = {
		"com.mojang.renderpearl.frontend.shaders.GlslCompiler",
		"com.mojang.renderpearl.frontend.shaders.SPIRVModule"
	};
	private static final Map<ShaderSource, Map<Key, CompletableFuture<byte[]>>> CACHE = new WeakHashMap<>();
	private static final AtomicInteger COMPILED = new AtomicInteger();
	private static final AtomicInteger SHARED = new AtomicInteger();

	private SpirvCache() {
	}

	public interface Compile {
		SpvModule call() throws Exception;
	}

	private record Key(Object compiler, String name, String source, ShaderType type, ShaderDefines defines) {
	}

	public static SpvModule compile(
		final Object compiler,
		final String name,
		final String source,
		final ShaderType type,
		final ShaderDefines defines,
		final ShaderSource shaderSource,
		final Compile original
	) throws Exception {
		if (!RpoSettings.active() || !ReuseGuard.untouched("compiled shaders", CLASSES)) {
			return original.call();
		}

		Map<Key, CompletableFuture<byte[]>> compiled;
		synchronized (CACHE) {
			compiled = CACHE.computeIfAbsent(shaderSource, s -> new ConcurrentHashMap<>());
		}

		Key key = new Key(compiler, name, source, type, defines);
		CompletableFuture<byte[]> mine = new CompletableFuture<>();
		CompletableFuture<byte[]> existing = compiled.putIfAbsent(key, mine);
		if (existing != null) {
			byte[] bytes;
			try {
				bytes = existing.join();
			} catch (CompletionException e) {
				return original.call();
			}

			ByteBuffer copy = MemoryUtil.memAlloc(bytes.length);
			copy.put(0, bytes);
			SHARED.incrementAndGet();
			return new SPIRVModule(copy, type);
		}

		SpvModule module;
		try {
			module = original.call();
			ByteBuffer spv = module.spv();
			byte[] bytes = new byte[spv.remaining()];
			spv.get(spv.position(), bytes);
			mine.complete(bytes);
		} catch (Throwable t) {
			compiled.remove(key, mine);
			mine.completeExceptionally(t);
			throw t;
		}

		COMPILED.incrementAndGet();
		return module;
	}

	public static String stats() {
		return COMPILED.get() + " compiled, " + SHARED.get() + " shared";
	}

	public static void clear() {
		synchronized (CACHE) {
			CACHE.clear();
		}
	}
}
