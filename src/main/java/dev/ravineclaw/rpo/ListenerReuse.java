package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.StitchResultAccessor;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

public final class ListenerReuse {
	private ListenerReuse() {
	}

	public static CompletableFuture<Boolean> inputsMatch(
		final @Nullable InputRecording previous, final ResourceManager manager, final Executor executor
	) {
		if (previous == null) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return CompletableFuture.supplyAsync(() -> previous.matches(manager), executor).exceptionally(t -> Boolean.FALSE);
	}

	public static CompletableFuture<Boolean> canKeep(
		final @Nullable InputRecording previous,
		final ResourceManager manager,
		final Executor executor,
		final Collection<CompletableFuture<AtlasSet.StitchResult>> atlases
	) {
		return inputsMatch(previous, manager, executor).thenCompose(same -> {
			if (!same) {
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}

			return allAtlasesKept(atlases);
		}).exceptionally(t -> Boolean.FALSE);
	}

	public static CompletableFuture<Boolean> allAtlasesKept(final Collection<CompletableFuture<AtlasSet.StitchResult>> atlases) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		CompletableFuture<?>[] checks = new CompletableFuture<?>[atlases.size()];
		int i = 0;
		for (CompletableFuture<AtlasSet.StitchResult> atlas : atlases) {
			checks[i++] = atlas.thenAccept(stitched -> {
				if (!AtlasReuse.isUploaded(((StitchResultAccessor)stitched).rpo$getPreparations())) {
					result.complete(Boolean.FALSE);
				}
			});
		}

		CompletableFuture.allOf(checks).whenComplete((unused, t) -> result.complete(t == null));
		return result;
	}
}
