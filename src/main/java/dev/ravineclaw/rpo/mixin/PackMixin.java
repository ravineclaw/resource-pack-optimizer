package dev.ravineclaw.rpo.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Pack.class)
public abstract class PackMixin {
	@Unique
	private static final Map<String, Pack.Metadata> RPO_METADATA_CACHE = new ConcurrentHashMap<>();

	@Unique
	private static @Nullable String rpo$cacheKey(final Pack.ResourcesSupplier resources, final int currentPackVersion) {
		try {
			Path fingerprinted;
			String kind;
			if (resources instanceof FilePackResources.FileResourcesSupplier) {
				fingerprinted = ((FileResourcesSupplierAccessor)resources).rpo$getContent().toPath();
				kind = "zip";
			} else if (resources instanceof PathPackResources.PathResourcesSupplier) {
				fingerprinted = ((PathResourcesSupplierAccessor)resources).rpo$getContent().resolve("pack.mcmeta");
				kind = "dir";
			} else {
				return null;
			}

			BasicFileAttributes attributes = Files.readAttributes(fingerprinted, BasicFileAttributes.class);
			if (!attributes.isRegularFile()) {
				return null;
			}

			return kind + '|' + fingerprinted.toAbsolutePath() + '|' + attributes.size() + '|' + attributes.lastModifiedTime().toMillis() + '|' + currentPackVersion;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	@Inject(method = "readPackMetadata", at = @At("HEAD"), cancellable = true)
	private static void rpo$cachedMetadata(
		final PackLocationInfo location,
		final Pack.ResourcesSupplier resources,
		final int currentPackVersion,
		final CallbackInfoReturnable<Pack.Metadata> cir
	) {
		String key = rpo$cacheKey(resources, currentPackVersion);
		if (key != null) {
			Pack.Metadata cached = RPO_METADATA_CACHE.get(key);
			if (cached != null) {
				cir.setReturnValue(cached);
			}
		}
	}

	@Inject(method = "readPackMetadata", at = @At("RETURN"))
	private static void rpo$storeMetadata(
		final PackLocationInfo location,
		final Pack.ResourcesSupplier resources,
		final int currentPackVersion,
		final CallbackInfoReturnable<Pack.Metadata> cir
	) {
		Pack.Metadata metadata = cir.getReturnValue();
		if (metadata != null) {
			String key = rpo$cacheKey(resources, currentPackVersion);
			if (key != null) {
				RPO_METADATA_CACHE.put(key, metadata);
			}
		}
	}
}
