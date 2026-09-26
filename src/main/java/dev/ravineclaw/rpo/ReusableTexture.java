package dev.ravineclaw.rpo;

import net.minecraft.client.renderer.texture.TextureContents;
import org.jetbrains.annotations.Nullable;

public interface ReusableTexture {
	TextureContents KEEP = new TextureContents(null, null);

	@Nullable InputRecording rpo$applied();

	void rpo$setPending(TextureContents contents, InputRecording recording);
}
