package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PostChainReset;
import dev.ravineclaw.rpo.ShaderCacheOwner;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class CompilationCacheMixin implements PostChainReset {
	@Shadow
	@Final
	Map<ResourceLocation, Optional<PostChain>> postChains;
	@Shadow
	boolean triggeredRecovery;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rpo$register(final ShaderManager owner, final ShaderManager.Configs configs, final CallbackInfo ci) {
		((ShaderCacheOwner)owner).rpo$cacheCreated(this);
	}

	@Override
	public void rpo$reset() {
		this.postChains.clear();
		this.triggeredRecovery = false;
	}
}
