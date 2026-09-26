package dev.ravineclaw.rpo.mixin;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.HttpUtil;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin {
	@Unique
	private static final Map<String, HashCode> RPO_HASHES = new ConcurrentHashMap<>();

	@Unique
	private static @Nullable String rpo$key(final Path file, final HashFunction hashFunction) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			if (!attributes.isRegularFile()) {
				return null;
			}

			return file.toAbsolutePath() + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis() + "|" + hashFunction;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	@Inject(method = "hashFile", at = @At("HEAD"), cancellable = true)
	private static void rpo$cachedHash(final Path file, final HashFunction hashFunction, final CallbackInfoReturnable<HashCode> cir) {
		String key = rpo$key(file, hashFunction);
		if (key != null) {
			HashCode cached = RPO_HASHES.get(key);
			if (cached != null) {
				cir.setReturnValue(cached);
			}
		}
	}

	@Inject(method = "hashFile", at = @At("RETURN"))
	private static void rpo$storeHash(final Path file, final HashFunction hashFunction, final CallbackInfoReturnable<HashCode> cir) {
		String key = rpo$key(file, hashFunction);
		if (key != null && cir.getReturnValue() != null) {
			RPO_HASHES.put(key, cir.getReturnValue());
		}
	}
}
