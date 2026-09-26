package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReusableTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReloadableTexture.class)
public abstract class ReloadableTextureMixin implements ReusableTexture {
	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable TextureContents rpo$pendingContents;
	@Unique
	private volatile @Nullable InputRecording rpo$pendingRecording;

	@Inject(method = "apply", at = @At("HEAD"), cancellable = true)
	private void rpo$keep(final TextureContents contents, final CallbackInfo ci) {
		if (contents == KEEP) {
			ci.cancel();
			return;
		}

		this.rpo$applied = null;
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void rpo$rememberApplied(final TextureContents contents, final CallbackInfo ci) {
		this.rpo$applied = contents == this.rpo$pendingContents ? this.rpo$pendingRecording : null;
		this.rpo$pendingContents = null;
		this.rpo$pendingRecording = null;
	}

	@Override
	public @Nullable InputRecording rpo$applied() {
		return this.rpo$applied;
	}

	@Override
	public void rpo$setPending(final TextureContents contents, final InputRecording recording) {
		this.rpo$pendingContents = contents;
		this.rpo$pendingRecording = recording;
	}
}
