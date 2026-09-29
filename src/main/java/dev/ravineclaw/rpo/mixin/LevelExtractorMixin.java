package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.TerrainHandoff;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnneededRebuild(final CallbackInfo ci) {
		TerrainHandoff.request(false);
		if (RpoSettings.active() && ReloadChanges.isFinishingReload() && ReloadChanges.meshInputsUnchanged() && ReuseGuard.untouched("chunk meshes", ReuseGuard.CHUNKS)) {
			ResourcePackOptimizer.LOGGER.debug("Nothing chunk meshes depend on changed; keeping the built chunks");
			ci.cancel();
			return;
		}

		TerrainHandoff.request(ReloadChanges.isFinishingReload());
	}
}
