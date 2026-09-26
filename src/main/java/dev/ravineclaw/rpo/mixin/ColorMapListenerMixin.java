package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import java.util.Arrays;
import net.minecraft.client.resources.FoliageColorReloadListener;
import net.minecraft.client.resources.GrassColorReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GrassColorReloadListener.class, FoliageColorReloadListener.class})
public abstract class ColorMapListenerMixin {
	@Unique
	private int @Nullable [] rpo$previous;

	@Inject(method = "apply([ILnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
	private void rpo$compare(final int[] pixels, final ResourceManager manager, final ProfilerFiller profiler, final CallbackInfo ci) {
		if (this.rpo$previous != null && Arrays.equals(this.rpo$previous, pixels)) {
			Object self = this;
			ReloadChanges.unchanged("colormap:" + (self instanceof GrassColorReloadListener ? "grass" : "foliage"));
		}

		this.rpo$previous = pixels;
	}
}
