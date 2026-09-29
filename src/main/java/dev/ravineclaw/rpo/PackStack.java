package dev.ravineclaw.rpo;

import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.jetbrains.annotations.Nullable;

public interface PackStack {
	long[] UNVERSIONED = new long[0];

	PackType rpo$type();

	List<PackResources> rpo$packs();

	List<String> rpo$filters();

	long @Nullable [] rpo$versions();

	void rpo$setVersions(long[] versions);

	PackFingerprints.@Nullable StackSources rpo$sources();

	void rpo$setSources(PackFingerprints.StackSources sources);
}
