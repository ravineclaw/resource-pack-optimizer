package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.ReuseGuard;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnneededRebuild(final CallbackInfo ci) {
		if (ReloadChanges.isFinishingReload() && ReloadChanges.meshInputsUnchanged() && ReuseGuard.untouched("chunk meshes", ReuseGuard.CHUNKS)) {
			ResourcePackOptimizer.LOGGER.debug("Nothing chunk meshes depend on changed; keeping the built chunks");
			ci.cancel();
		}
	}
}
