package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.PendingStitchResultsAccessor;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;

public final class ListenerReuse {
	private ListenerReuse() {
	}

	public static CompletableFuture<Boolean> inputsMatch(
		final @Nullable InputRecording previous, final PreparableReloadListener.SharedState currentReload, final Executor executor
	) {
		if (previous == null) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return CompletableFuture.supplyAsync(() -> previous.matches(currentReload.resourceManager()), executor).exceptionally(t -> Boolean.FALSE);
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

			return allAtlasesKept(currentReload);
		}).exceptionally(t -> Boolean.FALSE);
	}

	public static CompletableFuture<Boolean> allAtlasesKept(final PreparableReloadListener.SharedState currentReload) {
		Collection<CompletableFuture<SpriteLoader.Preparations>> atlases =
			((PendingStitchResultsAccessor)currentReload.get(AtlasManager.PENDING_STITCH)).rpo$getStitchFuturesById().values();
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		CompletableFuture<?>[] checks = new CompletableFuture<?>[atlases.size()];
		int i = 0;
		for (CompletableFuture<SpriteLoader.Preparations> atlas : atlases) {
			checks[i++] = atlas.thenAccept(preparations -> {
				if (!AtlasReuse.isUploaded(preparations)) {
					result.complete(Boolean.FALSE);
				}
			});
		}

		CompletableFuture.allOf(checks).whenComplete((unused, t) -> result.complete(t == null));
		return result;
	}
}
