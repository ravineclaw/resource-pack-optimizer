package dev.ravineclaw.rpo.mixin;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(I18n.class)
public interface I18nInvoker {
	@Invoker("setLanguage")
	static void rpo$setLanguage(final Language language) {
		throw new AssertionError();
	}
}
