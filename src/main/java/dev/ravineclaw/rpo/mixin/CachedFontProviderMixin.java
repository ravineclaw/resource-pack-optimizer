package dev.ravineclaw.rpo.mixin;

import dev.ravineclaw.rpo.GlyphCacheReset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class CachedFontProviderMixin implements GlyphCacheReset {
	@Shadow
	public abstract void invalidate();

	@Override
	public void rpo$invalidate() {
		this.invalidate();
	}
}
