package dev.ravineclaw.rpo;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public interface SoftSoundReload {
	void rpo$softReload();

	void rpo$keepSounds(Set<ResourceLocation> reopened);
}
