package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.SoftSoundReload;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Shadow
	@Final
	private Map<Identifier, Resource> soundCache;

	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable InputRecording rpo$pending;
	@Unique
	private volatile boolean rpo$unchanged;

	@ModifyVariable(
		method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Lnet/minecraft/client/sounds/SoundManager$Preparations;",
		at = @At("HEAD"),
		argsOnly = true
	)
	private ResourceManager rpo$recordReads(final ResourceManager manager) {
		this.rpo$pending = null;
		this.rpo$unchanged = false;
		if (!RpoSettings.active() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("sounds", ReuseGuard.SOUNDS)) {
			return manager;
		}

		InputRecording applied = this.rpo$applied;
		this.rpo$unchanged = applied != null && applied.matches(manager);
		InputRecording pending = InputRecording.start(manager);
		this.rpo$pending = pending;
		return pending.manager();
	}

	@Inject(method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
	private void rpo$forgetApplied(final CallbackInfo ci) {
		this.rpo$applied = null;
	}

	@Inject(method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
	private void rpo$rememberApplied(final CallbackInfo ci) {
		this.rpo$applied = this.rpo$pending;
		this.rpo$pending = null;
	}

	@Redirect(
		method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;reload()V")
	)
	private void rpo$softReload(final SoundEngine engine) {
		if (!RpoSettings.active()) {
			engine.reload();
		} else if (this.rpo$unchanged && this.rpo$pending != null) {
			ReloadChanges.unchanged("sounds");
			Set<Identifier> reopened = new HashSet<>();
			for (Map.Entry<Identifier, Resource> entry : this.soundCache.entrySet()) {
				if (!rpo$survivesClose(entry.getValue().source())) {
					reopened.add(entry.getKey());
				}
			}

			((SoftSoundReload)engine).rpo$keepSounds(reopened);
		} else {
			((SoftSoundReload)engine).rpo$softReload();
		}
	}

	@Unique
	private static boolean rpo$survivesClose(final PackResources pack) {
		if (pack instanceof PathPackResources) {
			return true;
		}

		if (pack instanceof CompositePackResources overlayed) {
			for (PackResources layer : ((CompositePackResourcesAccessor)overlayed).rpo$getPackResourcesStack()) {
				if (!rpo$survivesClose(layer)) {
					return false;
				}
			}

			return true;
		}

		return false;
	}
}
