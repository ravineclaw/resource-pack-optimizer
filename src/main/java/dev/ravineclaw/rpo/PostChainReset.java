package dev.ravineclaw.rpo;

import org.jspecify.annotations.Nullable;

public interface PostChainReset {
	void rpo$reset();

	final class Created {
		private static volatile @Nullable PostChainReset last;

		private Created() {
		}

		public static void record(final PostChainReset cache) {
			last = cache;
		}

		public static @Nullable PostChainReset last() {
			return last;
		}
	}
}
