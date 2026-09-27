package dev.ravineclaw.rpo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ToggleScreen {
	private ToggleScreen() {
	}

	public static Screen create(final Screen parent) {
		boolean on = RpoSettings.enabledInFile();
		boolean restart = on == RpoSettings.disabledThisSession();
		Component title = Component.literal("Resource Pack Optimizer");
		Component message = Component.literal((on ? "It is turned on." : "It is turned off.") + (restart ? " Restart the game for this to take effect." : ""));
		return new ConfirmScreen(
			change -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (change) {
					RpoSettings.setEnabled(!on);
					minecraft.gui.setScreen(create(parent));
				} else {
					minecraft.gui.setScreen(parent);
				}
			},
			title,
			message,
			Component.literal(on ? "Turn off" : "Turn on"),
			Component.literal("Done")
		);
	}
}
