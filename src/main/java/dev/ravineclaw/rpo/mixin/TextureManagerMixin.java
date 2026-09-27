package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReusableTexture;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import java.io.IOException;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
	@Unique
	private static final ThreadLocal<Boolean> RPO_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Shadow
	private static TextureContents loadContents(final ResourceManager manager, final Identifier location, final ReloadableTexture texture) throws IOException {
		throw new AssertionError();
	}

	@Inject(method = "loadContents", at = @At("HEAD"), cancellable = true)
	private static void rpo$keepUnchanged(
		final ResourceManager manager, final Identifier location, final ReloadableTexture texture, final CallbackInfoReturnable<TextureContents> cir
	) throws IOException {
		if (RPO_BYPASS.get()
			|| !RpoSettings.active()
			|| texture.getClass() != SimpleTexture.class && texture.getClass() != CubeMapTexture.class
			|| !InputRecording.isTrackable(manager)
			|| !ReuseGuard.untouched("textures", ReuseGuard.TEXTURES)) {
			return;
		}

		ReusableTexture reusable = (ReusableTexture)texture;
		InputRecording applied = reusable.rpo$applied();
		if (applied != null && applied.matches(manager)) {
			cir.setReturnValue(ReusableTexture.KEEP);
			return;
		}

		InputRecording recording = InputRecording.start(manager);
		TextureContents contents;
		RPO_BYPASS.set(Boolean.TRUE);
		try {
			contents = loadContents(recording.manager(), location, texture);
		} finally {
			RPO_BYPASS.set(Boolean.FALSE);
		}

		reusable.rpo$setPending(contents, recording);
		cir.setReturnValue(contents);
	}
}
