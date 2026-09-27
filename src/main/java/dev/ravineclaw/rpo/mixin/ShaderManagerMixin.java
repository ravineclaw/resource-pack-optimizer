package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PostChainReset;
import dev.ravineclaw.rpo.ResourcePackOptimizer;
import dev.ravineclaw.rpo.RpoSettings;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
	@Unique
	private static @Nullable Field rpo$postChainsField;

	@Unique
	private volatile @Nullable Map<Identifier, String> rpo$lastFingerprint;
	@Unique
	private volatile @Nullable Map<Identifier, String> rpo$pendingFingerprint;
	@Unique
	private volatile boolean rpo$runVanilla;

	@Inject(method = "reload", at = @At("HEAD"), cancellable = true)
	private void rpo$skipUnchanged(
		final PreparableReloadListener.SharedState currentReload,
		final Executor taskExecutor,
		final PreparableReloadListener.PreparationBarrier preparationBarrier,
		final Executor reloadExecutor,
		final CallbackInfoReturnable<CompletableFuture<Void>> cir
	) {
		if (this.rpo$runVanilla) {
			this.rpo$runVanilla = false;
			return;
		}

		if (!RpoSettings.active()) {
			this.rpo$lastFingerprint = null;
			this.rpo$pendingFingerprint = null;
			return;
		}

		ShaderManager self = (ShaderManager)(Object)this;
		ResourceManager manager = currentReload.resourceManager();
		cir.setReturnValue(CompletableFuture.supplyAsync(() -> rpo$fingerprint(manager), taskExecutor).thenCompose(fingerprint -> {
			Map<Identifier, String> last = this.rpo$lastFingerprint;
			if (fingerprint != null && fingerprint.equals(last)) {
				return preparationBarrier.wait(Unit.INSTANCE).thenAcceptAsync(unused -> this.rpo$resetPostChains(), reloadExecutor);
			}

			this.rpo$pendingFingerprint = fingerprint;
			this.rpo$runVanilla = true;
			return self.reload(currentReload, taskExecutor, preparationBarrier, reloadExecutor);
		}));
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void rpo$rememberFingerprint(final CallbackInfo ci) {
		this.rpo$lastFingerprint = this.rpo$pendingFingerprint;
		this.rpo$pendingFingerprint = null;
	}

	@Inject(method = "tryTriggerRecovery", at = @At("HEAD"))
	private void rpo$forgetOnRecovery(final CallbackInfo ci) {
		this.rpo$lastFingerprint = null;
	}

	@Unique
	private static @Nullable Map<Identifier, String> rpo$fingerprint(final ResourceManager manager) {
		try {
			Map<Identifier, String> contents = new HashMap<>();
			for (Map.Entry<Identifier, Resource> entry : manager.listResources("shaders", id -> true).entrySet()) {
				contents.put(entry.getKey(), entry.getValue().readAllAsString());
			}

			for (Map.Entry<Identifier, Resource> entry : manager.listResources("post_effect", id -> true).entrySet()) {
				contents.put(entry.getKey(), entry.getValue().readAllAsString());
			}

			return contents;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	@Unique
	private void rpo$resetPostChains() {
		try {
			Field field = rpo$postChainsField;
			if (field == null) {
				field = ShaderManager.class.getDeclaredField("postChains");
				field.setAccessible(true);
				rpo$postChainsField = field;
			}

			((PostChainReset)field.get(this)).rpo$reset();
		} catch (ReflectiveOperationException | RuntimeException e) {
			ResourcePackOptimizer.LOGGER.warn("Couldn't reset post effect chains", e);
		}
	}
}
