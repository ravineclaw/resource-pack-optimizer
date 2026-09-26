package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.ReloadChanges;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {
	@Shadow
	@Final
	private List<PreparableReloadListener> listeners;

	@Inject(method = "createReload", at = @At("HEAD"))
	private void rpo$beginReload(
		final Executor backgroundExecutor,
		final Executor mainThreadExecutor,
		final CompletableFuture<Unit> initialTask,
		final List<PackResources> resourcePacks,
		final CallbackInfoReturnable<ReloadInstance> cir
	) {
		ReloadChanges.begin(this.listeners);
	}
}
