package dev.ravineclaw.rpo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

public final class ZipIndex {
	private static final Map<ZipFile, ZipIndex> INDEXES = new WeakHashMap<>();

	private final String[] names;
	private final ZipEntry[] entries;
	private final long contentHash;
	private final boolean duplicates;

	private ZipIndex(final ZipFile zipFile) {
		List<ZipEntry> all = new ArrayList<>(zipFile.size());
		Enumeration<? extends ZipEntry> it = zipFile.entries();
		while (it.hasMoreElements()) {
			all.add(it.nextElement());
		}

		all.sort((a, b) -> a.getName().compareTo(b.getName()));
		this.entries = all.toArray(ZipEntry[]::new);
		this.names = new String[this.entries.length];
		for (int i = 0; i < this.entries.length; i++) {
			this.names[i] = this.entries[i].getName();
		}

		long hash = PackFingerprints.hashStart();
		for (ZipEntry entry : this.entries) {
			hash = PackFingerprints.hashMix(hash, PackFingerprints.hashString(entry.getName()));
			hash = PackFingerprints.hashMix(hash, entry.getCrc());
			hash = PackFingerprints.hashMix(hash, entry.getSize());
		}

		this.contentHash = hash;
		boolean duplicates = false;
		for (int i = 1; i < this.names.length; i++) {
			duplicates |= this.names[i].equals(this.names[i - 1]);
		}

		this.duplicates = duplicates;
	}

	public @Nullable ZipEntry entry(final ZipFile zipFile, final String name) {
		if (this.duplicates) {
			return zipFile.getEntry(name);
		}

		int index = Arrays.binarySearch(this.names, name);
		if (index < 0 && !name.endsWith("/")) {
			index = Arrays.binarySearch(this.names, name + "/");
		}

		return index < 0 ? null : this.entries[index];
	}

	public long contentHash() {
		return this.contentHash;
	}

	public static ZipIndex of(final ZipFile zipFile) {
		synchronized (INDEXES) {
			ZipIndex index = INDEXES.get(zipFile);
			if (index == null) {
				index = new ZipIndex(zipFile);
				INDEXES.put(zipFile, index);
			}

			return index;
		}
	}

	public Enumeration<ZipEntry> entriesWithPrefix(final String prefix) {
		int start = Arrays.binarySearch(this.names, prefix);
		if (start < 0) {
			start = -start - 1;
		}

		int end = start;
		while (end < this.names.length && this.names[end].startsWith(prefix)) {
			end++;
		}

		if (start == end) {
			return Collections.emptyEnumeration();
		}

		final int from = start;
		final int to = end;
		return new Enumeration<>() {
			private int next = from;

			@Override
			public boolean hasMoreElements() {
				return this.next < to;
			}

			@Override
			public ZipEntry nextElement() {
				if (this.next >= to) {
					throw new NoSuchElementException();
				}

				return ZipIndex.this.entries[this.next++];
			}
		};
	}
}
