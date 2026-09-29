package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.TerrainHandoff;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private CloudRenderer cloudRenderer;
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow
	private @Nullable ViewArea viewArea;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
	@Shadow
	private @Nullable ClientLevel level;

	@Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnneededRebuild(final CallbackInfo ci) {
		TerrainHandoff.request(false);
		if (RpoSettings.active()
			&& this.viewArea != null
			&& this.sectionRenderDispatcher != null
			&& ReloadChanges.isFinishingReload()
			&& ReloadChanges.meshInputsUnchanged()
			&& ReuseGuard.untouched("chunk meshes", ReuseGuard.CHUNKS)) {
			ResourcePackOptimizer.LOGGER.debug("Nothing chunk meshes depend on changed; keeping the built chunks");
			ci.cancel();
			return;
		}

		TerrainHandoff.request(RpoSettings.active() && ReloadChanges.isFinishingReload());
		ClientLevel current = this.level;
		SectionRenderDispatcher sections = this.sectionRenderDispatcher;
		ViewArea area = this.viewArea;
		if (current == null || sections == null || area == null || area.getViewDistance() != this.minecraft.options.getEffectiveRenderDistance()) {
			TerrainHandoff.abort();
			return;
		}

		if (!TerrainHandoff.start(sections, area)) {
			TerrainHandoff.abort();
			return;
		}

		current.clearTintCaches();
		this.cloudRenderer.markForRebuild();
		ItemBlockRenderTypes.setFancy(Minecraft.useFancyGraphics());
		sections.clearCompileQueue();
		ci.cancel();
	}

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void rpo$abortHandoff(final CallbackInfo ci) {
		TerrainHandoff.abort();
	}

	@Inject(method = "prepareChunkRenders", at = @At("HEAD"))
	private void rpo$handoffFrame(final CallbackInfoReturnable<?> cir) {
		TerrainHandoff.frame(this.viewArea, this.visibleSections);
	}
}
