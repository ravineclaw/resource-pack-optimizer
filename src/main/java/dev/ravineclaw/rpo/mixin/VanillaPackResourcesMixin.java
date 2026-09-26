package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ImmutablePack;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin implements ImmutablePack {
	@Unique
	private static final ThreadLocal<Boolean> RPO_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Shadow
	@Final
	private List<Path> rootPaths;
	@Shadow
	@Final
	private Map<PackType, List<Path>> pathsForType;

	@Unique
	private volatile Boolean rpo$immutable;
	@Unique
	private final Map<String, List<Map.Entry<Identifier, IoSupplier<InputStream>>>> rpo$listings = new ConcurrentHashMap<>();
	@Unique
	private final Map<String, Optional<IoSupplier<InputStream>>> rpo$lookups = new ConcurrentHashMap<>();

	@Shadow
	public abstract void listResources(PackType type, String namespace, String directory, PackResources.ResourceOutput output);

	@Shadow
	public abstract IoSupplier<InputStream> getResource(PackType type, Identifier location);

	@Override
	public boolean rpo$isImmutable() {
		Boolean immutable = this.rpo$immutable;
		if (immutable == null) {
			boolean result = true;
			for (Path path : this.rootPaths) {
				result &= path.getFileSystem() != FileSystems.getDefault();
			}

			for (List<Path> paths : this.pathsForType.values()) {
				for (Path path : paths) {
					result &= path.getFileSystem() != FileSystems.getDefault();
				}
			}

			immutable = result;
			this.rpo$immutable = immutable;
		}

		return immutable;
	}

	@Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
	private void rpo$cachedListResources(
		final PackType type, final String namespace, final String directory, final PackResources.ResourceOutput output, final CallbackInfo ci
	) {
		if (RPO_BYPASS.get() || !this.rpo$isImmutable()) {
			return;
		}

		String key = type.name() + '\n' + namespace + '\n' + directory;
		List<Map.Entry<Identifier, IoSupplier<InputStream>>> listing = this.rpo$listings.get(key);
		if (listing == null) {
			List<Map.Entry<Identifier, IoSupplier<InputStream>>> collected = new ArrayList<>();
			RPO_BYPASS.set(Boolean.TRUE);
			try {
				this.listResources(type, namespace, directory, (id, resource) -> collected.add(Map.entry(id, resource)));
			} finally {
				RPO_BYPASS.set(Boolean.FALSE);
			}

			listing = List.copyOf(collected);
			this.rpo$listings.putIfAbsent(key, listing);
		}

		for (Map.Entry<Identifier, IoSupplier<InputStream>> entry : listing) {
			output.accept(entry.getKey(), entry.getValue());
		}

		ci.cancel();
	}

	@Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
	private void rpo$cachedGetResource(final PackType type, final Identifier location, final CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
		if (RPO_BYPASS.get() || !this.rpo$isImmutable()) {
			return;
		}

		String key = type.name() + '\n' + location;
		Optional<IoSupplier<InputStream>> lookup = this.rpo$lookups.get(key);
		if (lookup == null) {
			RPO_BYPASS.set(Boolean.TRUE);
			try {
				lookup = Optional.ofNullable(this.getResource(type, location));
			} finally {
				RPO_BYPASS.set(Boolean.FALSE);
			}

			this.rpo$lookups.putIfAbsent(key, lookup);
		}

		cir.setReturnValue(lookup.orElse(null));
	}
}
