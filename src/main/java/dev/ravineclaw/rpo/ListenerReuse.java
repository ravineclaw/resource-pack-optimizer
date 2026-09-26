package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.PendingStitchResultsAccessor;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.model.AtlasManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;

public final class ListenerReuse {
	private ListenerReuse() {
	}

	public static CompletableFuture<Boolean> canKeep(
		final @Nullable InputRecording previous, final PreparableReloadListener.SharedState currentReload, final Executor executor
	) {
		if (previous == null) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return CompletableFuture.supplyAsync(() -> previous.matches(currentReload.resourceManager()), executor).thenCompose(same -> {
			if (!same) {
				return CompletableFuture.completedFuture(Boolean.FALSE);
			}

			Collection<CompletableFuture<SpriteLoader.Preparations>> atlases =
				((PendingStitchResultsAccessor)currentReload.get(AtlasManager.PENDING_STITCH)).rpo$getStitchFuturesById().values();
			return CompletableFuture.allOf(atlases.toArray(CompletableFuture[]::new))
				.thenApply(unused -> atlases.stream().allMatch(atlas -> AtlasReuse.isUploaded(atlas.join())));
		}).exceptionally(t -> Boolean.FALSE);
	}
}
