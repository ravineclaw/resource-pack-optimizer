package dev.ravineclaw.rpo;

import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

public interface PackStack {
	PackType rpo$type();

	List<PackResources> rpo$packs();

	List<String> rpo$filters();
}
