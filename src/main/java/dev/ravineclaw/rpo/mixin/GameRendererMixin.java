package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.ShaderSources;
import java.util.Map;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Unique
	private @Nullable Map<ResourceLocation, byte[]> rpo$applied;
	@Unique
	private @Nullable Map<ResourceLocation, byte[]> rpo$pending;

	@Inject(method = "reloadShaders", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnchanged(final ResourceProvider provider, final CallbackInfo ci) {
		Map<ResourceLocation, byte[]> applied = this.rpo$applied;
		this.rpo$applied = null;
		this.rpo$pending = null;
		ShaderSources.beginCompile();
		if (!(provider instanceof GameRenderer.ResourceCache cache) || !ReuseGuard.untouched("shaders", ReuseGuard.SHADERS)) {
			return;
		}

		Map<ResourceLocation, byte[]> sources = ShaderSources.snapshot(cache.cache());
		if (ShaderSources.same(applied, sources)) {
			this.rpo$applied = applied;
			ci.cancel();
			return;
		}

		this.rpo$pending = sources;
	}

	@Inject(method = "reloadShaders", at = @At("RETURN"))
	private void rpo$rememberSources(final ResourceProvider provider, final CallbackInfo ci) {
		if (this.rpo$pending != null && ShaderSources.compiledFromCacheOnly()) {
			this.rpo$applied = this.rpo$pending;
		}

		this.rpo$pending = null;
	}
}
