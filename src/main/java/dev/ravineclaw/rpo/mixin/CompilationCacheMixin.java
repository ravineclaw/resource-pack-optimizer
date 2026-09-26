package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PostChainReset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class CompilationCacheMixin implements PostChainReset {
	@Shadow
	boolean triggeredRecovery;

	@Shadow
	public abstract void close();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rpo$recordCreated(final CallbackInfo ci) {
		PostChainReset.Created.record(this);
	}

	@Override
	public void rpo$reset() {
		this.close();
		this.triggeredRecovery = false;
	}
}
