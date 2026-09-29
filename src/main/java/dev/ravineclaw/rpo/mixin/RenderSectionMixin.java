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
	private SectionMesh setSectionMesh(final SectionMesh mesh) {
		throw new AssertionError();
	}

	@Shadow
	private void releaseSectionMesh(final SectionMesh mesh) {
		throw new AssertionError();
	}

	@WrapMethod(method = "setSectionMesh")
	private SectionMesh rpo$holdUntilHandoff(final SectionMesh mesh, final Operation<SectionMesh> original) {
		SectionMesh release = TerrainHandoff.hold((SectionRenderDispatcher.RenderSection)(Object)this, mesh);
		return release != null ? release : original.call(mesh);
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void rpo$dropHeld(final CallbackInfo ci) {
		TerrainHandoff.dropHeld((SectionRenderDispatcher.RenderSection)(Object)this);
	}

	@Override
	public void rpo$apply(final SectionMesh mesh) {
		this.releaseSectionMesh(this.setSectionMesh(mesh));
	}

	@Override
	public void rpo$release(final SectionMesh mesh) {
		this.releaseSectionMesh(mesh);
	}

	@Override
	public void rpo$demote() {
		this.releaseSectionMesh(this.sectionMesh.getAndSet(CompiledSectionMesh.UNCOMPILED));
		this.uploadedTime = 0L;
	}
}
