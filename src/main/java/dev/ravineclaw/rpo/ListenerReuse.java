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

	public static CompletableFuture<Boolean> canKeep(
		final @Nullable InputRecording previous,
		final ResourceManager manager,
		final Collection<CompletableFuture<AtlasSet.StitchResult>> atlases,
		final Executor executor
	) {
		if (previous == null) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return CompletableFuture.supplyAsync(() -> previous.matches(manager), executor).thenCompose(same -> {
			if (!same) {
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}

			return CompletableFuture.allOf(atlases.toArray(CompletableFuture[]::new))
				.thenApply(unused -> atlases.stream().allMatch(atlas -> AtlasReuse.isUploaded(((StitchResultAccessor)atlas.join()).rpo$getPreparations())));
		}).exceptionally(t -> Boolean.FALSE);
	}
}
