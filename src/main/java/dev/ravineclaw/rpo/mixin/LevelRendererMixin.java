package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	private @Nullable ViewArea viewArea;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnneededRebuild(final CallbackInfo ci) {
		if (RpoSettings.active()
			&& this.viewArea != null
			&& this.sectionRenderDispatcher != null
			&& ReloadChanges.isFinishingReload()
			&& ReloadChanges.meshInputsUnchanged()
			&& ReuseGuard.untouched("chunk meshes", ReuseGuard.CHUNKS)) {
			ResourcePackOptimizer.LOGGER.debug("Nothing chunk meshes depend on changed; keeping the built chunks");
			ci.cancel();
		}
	}
}
