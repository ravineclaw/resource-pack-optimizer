package dev.ravineclaw.rpo.mixin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.model.AtlasManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AtlasManager.PendingStitchResults.class)
public interface PendingStitchResultsAccessor {
	@Accessor("stitchFuturesById")
	Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> rpo$getStitchFuturesById();
}
