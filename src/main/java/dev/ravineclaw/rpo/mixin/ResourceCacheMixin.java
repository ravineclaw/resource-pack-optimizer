package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ShaderSources;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.ResourceCache.class)
public abstract class ResourceCacheMixin {
	@Shadow
	@Final
	private Map<ResourceLocation, Resource> cache;

	@Inject(method = "getResource", at = @At("HEAD"))
	private void rpo$noteOutsideLookup(final ResourceLocation location, final CallbackInfoReturnable<Optional<Resource>> cir) {
		if (!this.cache.containsKey(location)) {
			ShaderSources.lookedOutsideCache();
		}
	}
}
