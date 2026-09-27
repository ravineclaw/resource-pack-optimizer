package dev.ravineclaw.rpo;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class FramePump {
	private static final long BACKGROUND_BUDGET_NANOS = 4_000_000L;
	private static final Queue<Step> STEPS = new ConcurrentLinkedQueue<>();

	private FramePump() {
	}

	public interface Step {
		boolean run(long deadline);

		void fail(Throwable t);
	}

	public static void submit(final Step step) {
		STEPS.add(step);
	}

	public static void closeLater(final List<? extends AutoCloseable> resources) {
		if (!resources.isEmpty()) {
			submit(new Closer(new ArrayList<>(resources)));
		}
	}

	public static void pump() {
		if (STEPS.isEmpty()) {
			return;
		}

		long deadline = BackgroundReload.current() != null ? System.nanoTime() + BACKGROUND_BUDGET_NANOS : Long.MAX_VALUE;
		Step step;
		while ((step = STEPS.peek()) != null) {
			boolean done;
			try {
				done = step.run(deadline);
			} catch (Throwable t) {
				done = true;
				try {
					step.fail(t);
				} catch (Throwable ignored) {
				}
			}

			if (done) {
				STEPS.poll();
			}

			if (System.nanoTime() >= deadline) {
				return;
			}
		}
	}

	private static final class Closer implements Step {
		private final List<? extends AutoCloseable> resources;
		private int next;

		private Closer(final List<? extends AutoCloseable> resources) {
			this.resources = resources;
		}

		@Override
		public boolean run(final long deadline) {
			while (this.next < this.resources.size()) {
				if (System.nanoTime() >= deadline) {
					return false;
				}

				AutoCloseable resource = this.resources.get(this.next++);
				try {
					resource.close();
				} catch (Exception e) {
					ResourcePackOptimizer.LOGGER.debug("Couldn't close {}", resource, e);
				}
			}

			return true;
		}

		@Override
		public void fail(final Throwable t) {
			ResourcePackOptimizer.LOGGER.debug("Couldn't close old resources", t);
		}
	}
}
