package dev.ravineclaw.rpo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

public record ParsedModels(
	Map<ResourceLocation, BlockModel> models, Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>> blockStates
) {
	public static @Nullable ParsedModels of(
		final @Nullable CompletableFuture<Map<ResourceLocation, BlockModel>> models,
		final @Nullable CompletableFuture<Map<ResourceLocation, List<BlockStateModelLoader.LoadedJson>>> blockStates
	) {
		if (!done(models) || !done(blockStates)) {
			return null;
		}

		return new ParsedModels(models.join(), blockStates.join());
	}

	private static boolean done(final @Nullable CompletableFuture<?> future) {
		return future != null && future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
	}
}
