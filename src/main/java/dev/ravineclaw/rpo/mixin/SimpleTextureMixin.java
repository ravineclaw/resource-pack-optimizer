package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReuseGuard;
import java.io.IOException;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleTexture.class)
public abstract class SimpleTextureMixin {
	@Unique
	private static final ThreadLocal<Boolean> RPO_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Unique
	private volatile @Nullable InputRecording rpo$applied;

	@Shadow
	public abstract void load(ResourceManager resourceManager) throws IOException;

	@Inject(method = "load", at = @At("HEAD"), cancellable = true)
	private void rpo$keepUnchanged(final ResourceManager resourceManager, final CallbackInfo ci) throws IOException {
		if (RPO_BYPASS.get()) {
			return;
		}

		InputRecording applied = this.rpo$applied;
		this.rpo$applied = null;
		Object self = this;
		ResourceManager manager = resourceManager instanceof ReloadableResourceManager reloadable
			? ((ReloadableResourceManagerAccessor)reloadable).rpo$getResources()
			: resourceManager;
		if (self.getClass() != SimpleTexture.class || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("textures", ReuseGuard.TEXTURES)) {
			return;
		}

		ci.cancel();
		if (applied != null && applied.matches(manager)) {
			this.rpo$applied = applied;
			return;
		}

		InputRecording recording = InputRecording.start(manager);
		RPO_BYPASS.set(Boolean.TRUE);
		try {
			this.load(recording.manager());
		} finally {
			RPO_BYPASS.set(Boolean.FALSE);
		}

		this.rpo$applied = recording;
	}
}
