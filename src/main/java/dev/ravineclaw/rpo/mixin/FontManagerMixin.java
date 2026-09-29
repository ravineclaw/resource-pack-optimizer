package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.InputRecording;
import dev.ravineclaw.rpo.ListenerReuse;
import dev.ravineclaw.rpo.ReuseGuard;
import dev.ravineclaw.rpo.RpoSettings;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontManager.class)
public abstract class FontManagerMixin {
	@Unique
	private volatile @Nullable InputRecording rpo$applied;
	@Unique
	private volatile @Nullable InputRecording rpo$pending;
	@Unique
	private volatile boolean rpo$runVanilla;

	@Inject(method = "reload", at = @At("HEAD"), cancellable = true)
	private void rpo$keepUnchanged(
		final PreparableReloadListener.PreparationBarrier preparationBarrier,
		final ResourceManager manager,
		final ProfilerFiller preparationsProfiler,
		final ProfilerFiller reloadProfiler,
		final Executor taskExecutor,
		final Executor reloadExecutor,
		final CallbackInfoReturnable<CompletableFuture<Void>> cir
	) {
		if (this.rpo$runVanilla) {
			this.rpo$runVanilla = false;
			return;
		}

		if (!RpoSettings.active() || !InputRecording.isTrackable(manager) || !ReuseGuard.untouched("fonts", ReuseGuard.FONTS)) {
			this.rpo$applied = null;
			this.rpo$pending = null;
			return;
		}

		FontManager self = (FontManager)(Object)this;
		cir.setReturnValue(ListenerReuse.inputsMatch(this.rpo$applied, manager, taskExecutor).thenCompose(keep -> {
			if (keep) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> {
				}, reloadExecutor);
			}

			InputRecording recording = InputRecording.start(manager);
			this.rpo$pending = recording;
			this.rpo$runVanilla = true;
			return self.reload(preparationBarrier, recording.manager(), preparationsProfiler, reloadProfiler, taskExecutor, reloadExecutor);
		}));
	}

	@Inject(method = "apply", at = @At("HEAD"))
	private void rpo$forgetApplied(final CallbackInfo ci) {
		this.rpo$applied = null;
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void rpo$rememberApplied(final CallbackInfo ci) {
		this.rpo$applied = this.rpo$pending;
		this.rpo$pending = null;
	}
}
