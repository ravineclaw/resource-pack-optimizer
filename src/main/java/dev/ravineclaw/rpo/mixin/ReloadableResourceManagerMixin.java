package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.CurrentResources;
import dev.ravineclaw.rpo.PackFingerprints;
import dev.ravineclaw.rpo.ReloadChanges;
import dev.ravineclaw.rpo.RpoSettings;
import dev.ravineclaw.rpo.SpriteCache;
import dev.ravineclaw.rpo.SpriteDiskCache;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin implements CurrentResources {
	@Shadow
	@Final
	private List<PreparableReloadListener> listeners;
	@Shadow
	private CloseableResourceManager resources;

	@Inject(method = "createReload", at = @At("HEAD"))
	private void rpo$beginReload(
		final Executor backgroundExecutor,
		final Executor mainThreadExecutor,
		final CompletableFuture<Unit> initialTask,
		final List<PackResources> resourcePacks,
		final CallbackInfoReturnable<ReloadInstance> cir
	) {
		RpoSettings.beginReload();
		PackFingerprints.newReload();
		ReloadChanges.begin(this.listeners);
		SpriteCache.newGeneration();
		SpriteDiskCache.reloadStarted();
	}

	@Inject(method = "createReload", at = @At("RETURN"))
	private void rpo$endReload(
		final Executor backgroundExecutor,
		final Executor mainThreadExecutor,
		final CompletableFuture<Unit> initialTask,
		final List<PackResources> resourcePacks,
		final CallbackInfoReturnable<ReloadInstance> cir
	) {
		cir.getReturnValue().done().whenComplete((result, error) -> {
			SpriteDiskCache.reloadFinished();
		});
	}

	@Override
	public ResourceManager rpo$resources() {
		return this.resources;
	}
}
