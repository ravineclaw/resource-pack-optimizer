package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.PreparedLanguage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin implements PreparableReloadListener {
	@Shadow
	@Final
	private static LanguageInfo DEFAULT_LANGUAGE;
	@Shadow
	private Map<String, LanguageInfo> languages;
	@Shadow
	private String currentCode;
	@Shadow
	@Final
	private Consumer<ClientLanguage> reloadCallback;

	@Shadow
	private static Map<String, LanguageInfo> extractLanguages(final Stream<PackResources> resourcePacks) {
		throw new AssertionError();
	}

	@Shadow
	public abstract void onResourceManagerReload(ResourceManager resourceManager);

	@Override
	public CompletableFuture<Void> reload(
		final PreparableReloadListener.SharedState currentReload,
		final Executor taskExecutor,
		final PreparableReloadListener.PreparationBarrier preparationBarrier,
		final Executor reloadExecutor
	) {
		ResourceManager manager = currentReload.resourceManager();
		String code = this.currentCode;
		return CompletableFuture.supplyAsync(() -> rpo$prepare(manager, code), taskExecutor)
			.thenCompose(preparationBarrier::wait)
			.thenAcceptAsync(prepared -> {
				if (prepared.language() == null || !prepared.code().equals(this.currentCode)) {
					this.onResourceManagerReload(manager);
					return;
				}

				this.languages = prepared.languages();
				I18nInvoker.rpo$setLanguage(prepared.language());
				Language.inject(prepared.language());
				this.reloadCallback.accept(prepared.language());
			}, reloadExecutor);
	}

	@Unique
	private static PreparedLanguage rpo$prepare(final ResourceManager manager, final String code) {
		try {
			Map<String, LanguageInfo> languages = extractLanguages(manager.listPacks());
			List<String> languageStack = new ArrayList<>(2);
			languageStack.add("en_us");
			boolean rightToLeft = DEFAULT_LANGUAGE.bidirectional();
			if (!code.equals("en_us")) {
				LanguageInfo info = languages.get(code);
				if (info != null) {
					languageStack.add(code);
					rightToLeft = info.bidirectional();
				}
			}

			return new PreparedLanguage(code, languages, ClientLanguage.loadFrom(manager, languageStack, rightToLeft));
		} catch (Exception e) {
			return new PreparedLanguage(code, Map.of(), null);
		}
	}
}
