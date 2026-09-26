package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReusableTexture;
import dev.ravineclaw.rpo.ReuseGuard;
import java.io.IOException;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleTexture.class)
public abstract class SimpleTextureMixin extends AbstractTexture {
	@Unique
	private static final ThreadLocal<Boolean> RPO_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Inject(method = "load", at = @At("HEAD"), cancellable = true)
	private void rpo$keepUnchanged(final ResourceManager manager, final CallbackInfo ci) throws IOException {
		if (RPO_BYPASS.get()) {
			return;
		}

		ReusableTexture reusable = (ReusableTexture)this;
		if (((Object)this).getClass() != SimpleTexture.class || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("textures", ReuseGuard.TEXTURES)) {
			reusable.rpo$setApplied(null);
			return;
		}

		InputRecording applied = reusable.rpo$applied();
		if (applied != null && this.id != -1 && applied.matches(manager)) {
			ci.cancel();
			return;
		}

		reusable.rpo$setApplied(null);
		InputRecording recording = InputRecording.start(manager);
		RPO_BYPASS.set(Boolean.TRUE);
		try {
			this.load(recording.manager());
		} finally {
			RPO_BYPASS.set(Boolean.FALSE);
		}

		reusable.rpo$setApplied(recording.isUntrackable() ? null : recording);
		ci.cancel();
	}
}
