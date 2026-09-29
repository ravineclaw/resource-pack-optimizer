package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ravineclaw.rpo.TerrainHandoff;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin implements TerrainHandoff.Section {
	@Shadow
	@Final
	public AtomicReference<SectionMesh> sectionMesh;
	@Shadow
	private long uploadedTime;

	@Shadow
	abstract void setSectionMesh(final SectionMesh mesh);

	@Shadow
	protected abstract void cancelTasks();

	@Shadow
	public abstract void setDirty(final boolean fromPlayer);

	@WrapMethod(method = "setSectionMesh")
	private void rpo$holdUntilHandoff(final SectionMesh mesh, final Operation<Void> original) {
		SectionMesh release = TerrainHandoff.hold((SectionRenderDispatcher.RenderSection)(Object)this, mesh);
		if (release == null) {
			original.call(mesh);
		} else {
			TerrainHandoff.release(release);
		}
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void rpo$dropHeld(final CallbackInfo ci) {
		TerrainHandoff.dropHeld((SectionRenderDispatcher.RenderSection)(Object)this);
	}

	@Override
	public void rpo$apply(final SectionMesh mesh) {
		this.setSectionMesh(mesh);
	}

	@Override
	public void rpo$release(final SectionMesh mesh) {
		TerrainHandoff.release(mesh);
	}

	@Override
	public void rpo$demote() {
		TerrainHandoff.release(this.sectionMesh.getAndSet(CompiledSectionMesh.UNCOMPILED));
		this.uploadedTime = 0L;
		this.setDirty(false);
	}

	@Override
	public void rpo$invalidate() {
		this.cancelTasks();
		this.setDirty(false);
	}
}
