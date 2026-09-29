package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.Nullable;

public interface CachedSprite {
	NativeImage rpo$originalImage();

	void rpo$setCacheEntry(SpriteCache.@Nullable Entry entry);
}
