package dev.ravineclaw.rpo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public final class ReloadTimeline {
	public static final boolean ENABLED = Boolean.getBoolean("rpo.selftest");
	private static final Map<String, long[]> TIMES = new ConcurrentHashMap<>();
	private static volatile long start;
	private static volatile long lastFrame;
	private static volatile long longestFrame;

	private ReloadTimeline() {
	}

	public static void begin() {
		TIMES.clear();
		start = System.nanoTime();
	}

	public static void frame() {
		long now = System.nanoTime();
		long last = lastFrame;
		if (last != 0L && now - last > longestFrame) {
			longestFrame = now - last;
		}

		lastFrame = now;
	}

	public static void resetFrames() {
		lastFrame = 0L;
		longestFrame = 0L;
	}

	public static long longestFrameMillis() {
		return longestFrame / 1_000_000L;
	}

	public static PreparableReloadListener.PreparationBarrier timed(
		final PreparableReloadListener listener, final PreparableReloadListener.PreparationBarrier barrier
	) {
		return new TimedBarrier(listener, barrier);
	}

	private record TimedBarrier(PreparableReloadListener listener, PreparableReloadListener.PreparationBarrier barrier)
		implements PreparableReloadListener.PreparationBarrier {
		@Override
		public <T> CompletableFuture<T> wait(final T t) {
			times(this.listener)[0] = System.nanoTime() - start;
			return this.barrier.wait(t);
		}
	}

	public static Executor timedMainThread(final PreparableReloadListener listener, final Executor executor) {
		long[] times = times(listener);
		return task -> executor.execute(() -> {
			long taskStart = System.nanoTime();
			try {
				task.run();
			} finally {
				long took = System.nanoTime() - taskStart;
				times[2] += took;
				times[3] = Math.max(times[3], took);
			}
		});
	}

	public static void track(final PreparableReloadListener listener, final CompletableFuture<?> done) {
		long[] times = times(listener);
		done.whenComplete((result, error) -> times[1] = System.nanoTime() - start);
	}

	private static long[] times(final PreparableReloadListener listener) {
		return TIMES.computeIfAbsent(listener.getName(), name -> new long[] {-1L, -1L, 0L, 0L});
	}

	public static void log(final String label) {
		List<Map.Entry<String, long[]>> entries = new ArrayList<>(TIMES.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue()[0]));
		StringBuilder out = new StringBuilder("[selftest] ").append(label).append(" timeline (ms since reload start, prepared / applied; main-thread ms total / longest task):");
		for (Map.Entry<String, long[]> entry : entries) {
			out.append("\n    ")
				.append(String.format(
					"%-32s %7.1f %7.1f %7.1f %7.1f",
					entry.getKey(),
					entry.getValue()[0] / 1.0E6,
					entry.getValue()[1] / 1.0E6,
					entry.getValue()[2] / 1.0E6,
					entry.getValue()[3] / 1.0E6
				));
		}

		ResourcePackOptimizer.LOGGER.info(out.toString());
	}
}
