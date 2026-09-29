package dev.ravineclaw.rpo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.jetbrains.annotations.Nullable;

public final class AtlasReuse {
	public record Key(ResourceLocation definition, int maxMipmapLevels, int maxTextureSize, Set<MetadataSectionType<?>> metadata) {
	}

	public record Entry(Key key, InputRecording recording, SpriteLoader.Preparations preparations) {
	}

	private static final Map<ResourceLocation, Entry> UPLOADED = new ConcurrentHashMap<>();
	private static final Map<ResourceLocation, Entry> BUILT = new ConcurrentHashMap<>();

	private AtlasReuse() {
	}

	public static @Nullable Entry uploaded(final ResourceLocation atlas) {
		return UPLOADED.get(atlas);
	}

	public static void built(final ResourceLocation atlas, final Entry entry) {
		BUILT.put(atlas, entry);
	}

	public static boolean isUploaded(final ResourceLocation atlas, final SpriteLoader.Preparations preparations) {
		Entry entry = UPLOADED.get(atlas);
		return entry != null && entry.preparations() == preparations;
	}

	public static boolean isUploaded(final SpriteLoader.Preparations preparations) {
		for (Entry entry : UPLOADED.values()) {
			if (entry.preparations() == preparations) {
				return true;
			}
		}

		return false;
	}

	public static void uploadStarted(final ResourceLocation atlas) {
		UPLOADED.remove(atlas);
	}

	public static void uploadFinished(final ResourceLocation atlas, final SpriteLoader.Preparations preparations) {
		Entry built = BUILT.remove(atlas);
		if (built != null && built.preparations() == preparations && !built.recording().isUntrackable()) {
			UPLOADED.put(atlas, built);
		}
	}

	public static void clear() {
		UPLOADED.clear();
		BUILT.clear();
	}

	public static void forget(final ResourceLocation atlas) {
		UPLOADED.remove(atlas);
		BUILT.remove(atlas);
	}
}
