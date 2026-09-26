package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PostChainReset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$PostChainCache")
public abstract class PostChainCacheMixin implements PostChainReset {
	@Shadow
	private boolean triggeredRecovery;

	@Shadow
	public abstract void close();

	@Override
	public void rpo$reset() {
		this.close();
		this.triggeredRecovery = false;
	}
}
