package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.AtlasReuse;
import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReusableTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin implements ReusableTexture {
	@Unique
	private volatile @Nullable InputRecording rpo$applied;

	@Inject(method = "releaseId", at = @At("HEAD"))
	private void rpo$forgetContents(final CallbackInfo ci) {
		this.rpo$applied = null;
		if ((Object)this instanceof TextureAtlas atlas) {
			AtlasReuse.forget(atlas.location());
		}
	}

	@Override
	public @Nullable InputRecording rpo$applied() {
		return this.rpo$applied;
	}

	@Override
	public void rpo$setApplied(final @Nullable InputRecording recording) {
		this.rpo$applied = recording;
	}
}
