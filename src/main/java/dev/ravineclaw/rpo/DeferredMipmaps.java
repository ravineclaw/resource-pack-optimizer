package dev.ravineclaw.rpo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record DeferredMipmaps(Runnable task, Executor executor, CompletableFuture<Void> placeholder) {
	public void runVanilla() {
		CompletableFuture.runAsync(this.task, this.executor).whenComplete((unused, error) -> this.completeWith(error));
	}

	public void completeWith(final Throwable error) {
		if (error != null) {
			this.placeholder.completeExceptionally(error);
		} else {
			this.placeholder.complete(null);
		}
	}
}
