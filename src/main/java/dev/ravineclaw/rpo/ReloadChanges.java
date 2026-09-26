package dev.ravineclaw.rpo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public final class ReloadChanges {
	public static final String BLOCK_ATLAS = "atlas:minecraft:textures/atlas/blocks.png";
	public static final String MODELS = "models";
	private static final Set<String> MESH_INPUTS = Set.of(BLOCK_ATLAS, MODELS, "colormap:grass", "colormap:foliage", "colormap:dry_foliage");

	private static final Set<String> UNCHANGED = ConcurrentHashMap.newKeySet();
	private static volatile boolean onlyVanillaListeners;
	private static final ThreadLocal<Boolean> FINISHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private ReloadChanges() {
	}

	public static void begin(final List<PreparableReloadListener> listeners) {
		UNCHANGED.clear();
		boolean vanilla = true;
		for (PreparableReloadListener listener : listeners) {
			vanilla &= listener.getClass().getName().startsWith("net.minecraft.");
		}

		onlyVanillaListeners = vanilla;
	}

	public static void unchanged(final String what) {
		UNCHANGED.add(what);
	}

	public static boolean isUnchanged(final String what) {
		return UNCHANGED.contains(what);
	}

	public static void finishReload(final Runnable finish) {
		FINISHING.set(Boolean.TRUE);
		try {
			finish.run();
		} finally {
			FINISHING.set(Boolean.FALSE);
		}
	}

	public static boolean isFinishingReload() {
		return FINISHING.get();
	}

	public static boolean meshInputsUnchanged() {
		return onlyVanillaListeners && UNCHANGED.containsAll(MESH_INPUTS);
	}
}
