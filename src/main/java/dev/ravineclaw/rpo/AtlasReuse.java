package dev.ravineclaw.rpo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.jspecify.annotations.Nullable;

public final class AtlasReuse {
	public record Key(Identifier definition, int maxMipmapLevels, int anisotropyBit, int maxTextureSize, Set<MetadataSectionType<?>> metadata) {
	}

	public record Entry(Key key, InputRecording recording, SpriteLoader.Preparations preparations) {
	}

	private static final Map<Identifier, Entry> UPLOADED = new ConcurrentHashMap<>();
	private static final Map<Identifier, Entry> BUILT = new ConcurrentHashMap<>();

	private AtlasReuse() {
	}

	public static @Nullable Entry uploaded(final Identifier atlas) {
		return UPLOADED.get(atlas);
	}

	public static void built(final Identifier atlas, final Entry entry) {
		BUILT.put(atlas, entry);
	}

	public static boolean isUploaded(final Identifier atlas, final SpriteLoader.Preparations preparations) {
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

	public static void uploadStarted(final Identifier atlas) {
		UPLOADED.remove(atlas);
	}

	public static void uploadFinished(final Identifier atlas, final SpriteLoader.Preparations preparations) {
		Entry built = BUILT.remove(atlas);
		if (built != null && built.preparations() == preparations && !built.recording().isUntrackable()) {
			UPLOADED.put(atlas, built);
		}
	}

	public static void clear() {
		UPLOADED.clear();
		BUILT.clear();
	}

	public static void forget(final Identifier atlas) {
		UPLOADED.remove(atlas);
		BUILT.remove(atlas);
	}
}
