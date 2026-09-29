package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.RpoSettings;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LanguageSelectScreen.class)
public abstract class LanguageSelectScreenMixin {
	@Redirect(method = "onDone", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<Void> rpo$reloadLanguageOnly(final Minecraft minecraft) {
		if (!RpoSettings.active()) {
			return minecraft.reloadResourcePacks();
		}

		minecraft.getLanguageManager().onResourceManagerReload(minecraft.getResourceManager());
		return CompletableFuture.completedFuture(null);
	}
}
