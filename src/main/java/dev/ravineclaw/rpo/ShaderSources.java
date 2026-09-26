package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

public final class ShaderSources {
	private static volatile boolean lookedOutside;

	private ShaderSources() {
	}

	public static void lookedOutsideCache() {
		lookedOutside = true;
	}

	public static void beginCompile() {
		lookedOutside = false;
	}

	public static boolean compiledFromCacheOnly() {
		return !lookedOutside;
	}

	public static @Nullable Map<ResourceLocation, byte[]> snapshot(final Map<ResourceLocation, Resource> cache) {
		Map<ResourceLocation, byte[]> result = new HashMap<>(cache.size());
		for (Map.Entry<ResourceLocation, Resource> entry : cache.entrySet()) {
			try (InputStream in = entry.getValue().open()) {
				result.put(entry.getKey(), in.readAllBytes());
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}

		return result;
	}

	public static boolean same(final @Nullable Map<ResourceLocation, byte[]> a, final @Nullable Map<ResourceLocation, byte[]> b) {
		if (a == null || b == null || a.size() != b.size()) {
			return false;
		}

		for (Map.Entry<ResourceLocation, byte[]> entry : a.entrySet()) {
			if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
				return false;
			}
		}

		return true;
	}
}
