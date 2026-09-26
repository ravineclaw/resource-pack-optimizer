package dev.ravineclaw.rpo;

import java.util.Map;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import org.jetbrains.annotations.Nullable;

public record PreparedLanguage(String code, Map<String, LanguageInfo> languages, @Nullable ClientLanguage language) {
}
