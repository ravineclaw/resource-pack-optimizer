package dev.ravineclaw.rpo;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public record ParsedModels(
	Map<ResourceLocation, UnbakedModel> models, BlockStateModelLoader.LoadedModels blockStates, ClientItemInfoLoader.LoadedClientInfos items
) {
	public static @Nullable ParsedModels of(
		final @Nullable CompletableFuture<Map<ResourceLocation, UnbakedModel>> models,
		final @Nullable CompletableFuture<BlockStateModelLoader.LoadedModels> blockStates,
		final @Nullable CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> items
	) {
		if (!done(models) || !done(blockStates) || !done(items)) {
			return null;
		}

		return new ParsedModels(models.join(), blockStates.join(), items.join());
	}

	private static boolean done(final @Nullable CompletableFuture<?> future) {
		return future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
	}
}
