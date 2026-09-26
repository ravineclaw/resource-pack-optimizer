package dev.ravineclaw.rpo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.jetbrains.annotations.Nullable;

public final class BackgroundReload {
	private static volatile @Nullable LoadingOverlay current;

	private BackgroundReload() {
	}

	public static @Nullable LoadingOverlay current() {
		return current;
	}

	public static boolean isCurrent(final Object overlay) {
		return current != null && current == overlay;
	}

	public static void start(final LoadingOverlay overlay) {
		current = overlay;
	}

	public static void clear() {
		current = null;
	}

	public static boolean finish(final Object overlay) {
		if (!isCurrent(overlay)) {
			return false;
		}

		current = null;
		return true;
	}

	public static void render(final GuiGraphics graphics, final float a) {
		LoadingOverlay overlay = current;
		if (overlay != null) {
			overlay.render(graphics, 0, 0, a);
		}
	}
}
