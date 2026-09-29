package dev.ravineclaw.rpo;

import java.util.Set;
import net.minecraft.resources.Identifier;

public interface SoftSoundReload {
	void rpo$softReload();

	void rpo$keepSounds(Set<Identifier> reopened);
}
