package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.PackFingerprints;
import dev.ravineclaw.rpo.PackStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceFilterSection;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPackResourceManager.class)
public abstract class MultiPackResourceManagerMixin implements PackStack {
	@Shadow
	@Final
	private List<PackResources> packs;

	@Unique
	private PackType rpo$type;
	@Unique
	private List<String> rpo$filters;
	@Unique
	private volatile long @Nullable [] rpo$versions;
	@Unique
	private volatile PackFingerprints.@Nullable StackSources rpo$sources;

	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/packs/resources/MultiPackResourceManager;getPackFilterSection(Lnet/minecraft/server/packs/PackResources;)Lnet/minecraft/server/packs/resources/ResourceFilterSection;"
		)
	)
	private ResourceFilterSection rpo$recordFilter(final MultiPackResourceManager self, final PackResources pack, final Operation<ResourceFilterSection> original) {
		ResourceFilterSection section = original.call(self, pack);
		if (this.rpo$filters == null) {
			this.rpo$filters = new ArrayList<>();
		}

		String encoded;
		if (section == null) {
			encoded = "";
		} else {
			try {
				encoded = ResourceFilterSection.TYPE.toJson(section).toString();
			} catch (RuntimeException e) {
				encoded = "unencodable:" + System.identityHashCode(section) + ":" + System.nanoTime();
			}
		}

		this.rpo$filters.add(encoded);
		return section;
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rpo$recordType(final PackType type, final List<PackResources> packs, final CallbackInfo ci) {
		this.rpo$type = type;
		if (this.rpo$filters == null || this.rpo$filters.size() != this.packs.size()) {
			this.rpo$filters = null;
		} else {
			this.rpo$filters = List.copyOf(this.rpo$filters);
		}
	}

	@Override
	public PackType rpo$type() {
		return this.rpo$type;
	}

	@Override
	public List<PackResources> rpo$packs() {
		return this.packs;
	}

	@Override
	public List<String> rpo$filters() {
		return this.rpo$filters;
	}

	@Override
	public long @Nullable [] rpo$versions() {
		return this.rpo$versions;
	}

	@Override
	public void rpo$setVersions(final long[] versions) {
		this.rpo$versions = versions;
	}

	@Override
	public PackFingerprints.@Nullable StackSources rpo$sources() {
		return this.rpo$sources;
	}

	@Override
	public void rpo$setSources(final PackFingerprints.StackSources sources) {
		this.rpo$sources = sources;
	}
}
