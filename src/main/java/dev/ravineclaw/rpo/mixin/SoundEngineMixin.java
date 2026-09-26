package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.SoftSoundReload;
import java.util.List;
import java.util.Set;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin implements SoftSoundReload {
	@Shadow
	@Final
	private static Logger LOGGER;
	@Shadow
	@Final
	private static Set<ResourceLocation> ONLY_WARN_ONCE;
	@Shadow
	@Final
	private SoundManager soundManager;
	@Shadow
	private boolean loaded;
	@Shadow
	@Final
	private SoundBufferLibrary soundBuffers;
	@Shadow
	@Final
	private List<Sound> preloadQueue;

	@Shadow
	public abstract void reload();

	@Shadow
	public abstract void stopAll();

	@Override
	public void rpo$softReload() {
		if (!this.loaded) {
			this.reload();
			return;
		}

		ONLY_WARN_ONCE.clear();
		for (SoundEvent sound : BuiltInRegistries.SOUND_EVENT) {
			if (sound != SoundEvents.EMPTY) {
				ResourceLocation location = sound.getLocation();
				if (this.soundManager.getSoundEvent(location) == null) {
					LOGGER.warn("Missing sound for event: {}", BuiltInRegistries.SOUND_EVENT.getKey(sound));
					ONLY_WARN_ONCE.add(location);
				}
			}
		}

		this.stopAll();
		this.soundBuffers.clear();
		this.soundBuffers.preload(this.preloadQueue).thenRun(this.preloadQueue::clear);
	}
}
