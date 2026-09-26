package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.SoftSoundReload;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Redirect(
		method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;reload()V")
	)
	private void rpo$softReload(final SoundEngine engine) {
		((SoftSoundReload)engine).rpo$softReload();
	}
}
