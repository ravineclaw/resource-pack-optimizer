package dev.ravineclaw.rpo;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

public final class InputRecording {
	private static final long PACK_END = -8L;
	private static final long FILTER_MARKER = -1_000_000L;
	private final PackType type;
	private final List<String> filters;
	private final Map<ResourceLocation, long[]> lookups = new ConcurrentHashMap<>();
	private final Map<ListingKey, Listing> listings = new ConcurrentHashMap<>();
	private volatile @Nullable Set<String> namespaces;
	private volatile boolean untrackable;
	private final RecordingResourceManager manager;
	private final long @Nullable [] versions;

	private InputRecording(final PackStack stack, final ResourceManager delegate) {
		this.type = stack.rpo$type();
		this.filters = nonEmpty(stack.rpo$filters());
		this.versions = versions(stack);
		this.manager = new RecordingResourceManager(delegate, this);
	}

	private static long @Nullable [] versions(final PackStack stack) {
		long[] versions = stack.rpo$versions();
		if (versions == null) {
			List<PackResources> packs = stack.rpo$packs();
			versions = new long[packs.size()];
			for (int i = 0; i < versions.length; i++) {
				versions[i] = PackFingerprints.version(packs.get(i), stack.rpo$type());
				if (versions[i] == PackFingerprints.UNKNOWN) {
					versions = PackStack.UNVERSIONED;
					break;
				}
			}

			stack.rpo$setVersions(versions);
		}

		return versions == PackStack.UNVERSIONED ? null : versions;
	}

	public static boolean isTrackable(final ResourceManager manager) {
		return manager instanceof PackStack stack && stack.rpo$type() != null && stack.rpo$filters() != null;
	}

	public static InputRecording start(final ResourceManager manager) {
		return new InputRecording((PackStack)manager, manager);
	}

	private static List<String> nonEmpty(final List<String> filters) {
		return filters.stream().filter(filter -> !filter.isEmpty()).toList();
	}

	public ResourceManager manager() {
		return this.manager;
	}

	public boolean matches(final ResourceManager other) {
		try {
			if (this.untrackable || !isTrackable(other)) {
				return false;
			}

			PackStack stack = (PackStack)other;
			if (stack.rpo$type() != this.type || !nonEmpty(stack.rpo$filters()).equals(this.filters)) {
				return false;
			}

			if (this.versions != null && Arrays.equals(this.versions, versions(stack))) {
				return true;
			}

			Set<String> namespaces = this.namespaces;
			if (namespaces != null && !namespaces.equals(other.getNamespaces())) {
				return false;
			}

			LongArrayList scratch = new LongArrayList();
			for (Map.Entry<ResourceLocation, long[]> lookup : this.lookups.entrySet()) {
				long[] now = signature(stack, lookup.getKey(), scratch);
				if (now == null || !Arrays.equals(now, lookup.getValue())) {
					return false;
				}
			}

			for (Map.Entry<ListingKey, Listing> entry : this.listings.entrySet()) {
				ListingKey key = entry.getKey();
				Set<ResourceLocation> ids = key.stacks()
					? other.listResourceStacks(key.directory(), key.selector()).keySet()
					: other.listResources(key.directory(), key.selector()).keySet();
				if (!entry.getValue().matches(stack, ids, scratch)) {
					return false;
				}
			}

			return true;
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.debug("Comparing reload inputs failed, reloading normally", t);
			return false;
		}
	}

	public boolean isUntrackable() {
		return this.untrackable;
	}

	public int size() {
		int size = this.lookups.size();
		for (Listing listing : this.listings.values()) {
			size += listing.ids().length;
		}

		return size;
	}

	void markUntrackable() {
		this.untrackable = true;
	}

	void recordLookup(final ResourceManager delegate, final ResourceLocation id) {
		if (this.untrackable || this.lookups.containsKey(id)) {
			return;
		}

		long[] signature = signature((PackStack)delegate, id, new LongArrayList());
		if (signature == null) {
			this.untrackable = true;
		} else {
			this.lookups.putIfAbsent(id, signature);
		}
	}

	void recordListing(final ResourceManager delegate, final String directory, final Predicate<ResourceLocation> selector, final boolean stacks, final Set<ResourceLocation> ids) {
		if (this.untrackable) {
			return;
		}

		ListingKey key = new ListingKey(directory, selector, stacks);
		if (this.listings.containsKey(key)) {
			return;
		}

		Listing listing = listing((PackStack)delegate, ids, new LongArrayList());
		if (listing == null) {
			this.untrackable = true;
		} else {
			this.listings.putIfAbsent(key, listing);
		}
	}

	void recordNamespaces(final Set<String> namespaces) {
		this.namespaces = Set.copyOf(namespaces);
	}

	private static long @Nullable [] signature(final PackStack stack, final ResourceLocation id, final LongArrayList scratch) {
		scratch.clear();
		appendSignature(stack, id, scratch);
		return scratch.contains(PackFingerprints.UNKNOWN) ? null : scratch.toLongArray();
	}

	private static void appendSignature(final PackStack stack, final ResourceLocation id, final LongArrayList out) {
		PackFingerprints.StackSources sources = stack.rpo$sources();
		if (sources == null) {
			sources = PackFingerprints.sources(stack.rpo$packs(), stack.rpo$type(), stack.rpo$filters());
			stack.rpo$setSources(sources);
		}

		PackType type = stack.rpo$type();
		ResourceLocation metadata = id.withPath(id.getPath() + ".mcmeta");
		String path = id.getNamespace() + "/" + id.getPath();
		String metadataPath = path + ".mcmeta";
		int filterIndex = 0;
		for (int i : sources.candidates(id.getNamespace())) {
			if (sources.filtered(i)) {
				out.add(FILTER_MARKER - filterIndex++);
			}

			int mark = out.size();
			sources.append(i, type, id, path, out);
			sources.append(i, type, metadata, metadataPath, out);
			boolean present = false;
			for (int j = mark; j < out.size(); j++) {
				present |= out.getLong(j) != PackFingerprints.ABSENT;
			}

			if (present) {
				out.add(PACK_END);
			} else {
				out.size(mark);
			}
		}
	}

	private static @Nullable Listing listing(final PackStack stack, final Set<ResourceLocation> ids, final LongArrayList scratch) {
		ResourceLocation[] sorted = ids.toArray(ResourceLocation[]::new);
		Arrays.sort(sorted);
		scratch.clear();
		for (ResourceLocation id : sorted) {
			appendSignature(stack, id, scratch);
		}

		return scratch.contains(PackFingerprints.UNKNOWN) ? null : new Listing(sorted, scratch.toLongArray());
	}

	private record ListingKey(String directory, Predicate<ResourceLocation> selector, boolean stacks) {
	}

	private record Listing(ResourceLocation[] ids, long[] signatures) {
		private boolean matches(final PackStack stack, final Set<ResourceLocation> now, final LongArrayList scratch) {
			if (now.size() != this.ids.length) {
				return false;
			}

			ResourceLocation[] sorted = now.toArray(ResourceLocation[]::new);
			Arrays.sort(sorted);
			if (!Arrays.equals(sorted, this.ids)) {
				return false;
			}

			int position = 0;
			for (ResourceLocation id : sorted) {
				scratch.clear();
				appendSignature(stack, id, scratch);
				int length = scratch.size();
				if (position + length > this.signatures.length
					|| scratch.contains(PackFingerprints.UNKNOWN)
					|| !Arrays.equals(scratch.elements(), 0, length, this.signatures, position, position + length)) {
					return false;
				}

				position += length;
			}

			return position == this.signatures.length;
		}

		@Override
		public boolean equals(final Object o) {
			return o instanceof Listing other && Arrays.equals(this.ids, other.ids) && Arrays.equals(this.signatures, other.signatures);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.signatures);
		}
	}

	private record RecordingResourceManager(ResourceManager delegate, InputRecording recording) implements ResourceManager {
		@Override
		public Set<String> getNamespaces() {
			Set<String> namespaces = this.delegate.getNamespaces();
			this.recording.recordNamespaces(namespaces);
			return namespaces;
		}

		@Override
		public Optional<Resource> getResource(final ResourceLocation location) {
			this.recording.recordLookup(this.delegate, location);
			return this.delegate.getResource(location);
		}

		@Override
		public List<Resource> getResourceStack(final ResourceLocation location) {
			this.recording.recordLookup(this.delegate, location);
			return this.delegate.getResourceStack(location);
		}

		@Override
		public Map<ResourceLocation, Resource> listResources(final String directory, final Predicate<ResourceLocation> selector) {
			Map<ResourceLocation, Resource> result = this.delegate.listResources(directory, selector);
			this.recording.recordListing(this.delegate, directory, selector, false, result.keySet());
			return result;
		}

		@Override
		public Map<ResourceLocation, List<Resource>> listResourceStacks(final String directory, final Predicate<ResourceLocation> selector) {
			Map<ResourceLocation, List<Resource>> result = this.delegate.listResourceStacks(directory, selector);
			this.recording.recordListing(this.delegate, directory, selector, true, result.keySet());
			return result;
		}

		@Override
		public Stream<PackResources> listPacks() {
			this.recording.markUntrackable();
			return this.delegate.listPacks();
		}
	}
}
