package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PostChainReset;
import dev.ravineclaw.rpo.ShaderCacheOwner;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin implements ShaderCacheOwner {
	@Unique
	private ShaderManager.@Nullable Configs rpo$lastConfigs;
	@Unique
	private @Nullable PostChainReset rpo$createdCache;
	@Unique
	private @Nullable PostChainReset rpo$currentCache;

	@Override
	public void rpo$cacheCreated(final PostChainReset cache) {
		this.rpo$createdCache = cache;
	}

	@Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnchanged(final ShaderManager.Configs preparations, final ResourceManager manager, final ProfilerFiller profiler, final CallbackInfo ci) {
		ShaderManager.Configs last = this.rpo$lastConfigs;
		PostChainReset current = this.rpo$currentCache;
		this.rpo$lastConfigs = null;
		this.rpo$currentCache = null;
		boolean same;
		try {
			same = last != null && current != null && last.equals(preparations);
		} catch (RuntimeException e) {
			same = false;
		}

		if (same) {
			current.rpo$reset();
			this.rpo$lastConfigs = last;
			this.rpo$currentCache = current;
			ci.cancel();
		}
	}

	@Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
	private void rpo$rememberConfigs(final ShaderManager.Configs preparations, final ResourceManager manager, final ProfilerFiller profiler, final CallbackInfo ci) {
		this.rpo$lastConfigs = preparations;
		this.rpo$currentCache = this.rpo$createdCache;
	}

	@Inject(method = "tryTriggerRecovery", at = @At("HEAD"))
	private void rpo$forgetOnRecovery(final CallbackInfo ci) {
		this.rpo$lastConfigs = null;
		this.rpo$currentCache = null;
	}
}
