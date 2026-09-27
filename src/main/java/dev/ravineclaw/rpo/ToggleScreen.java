package dev.ravineclaw.rpo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ToggleScreen {
	private ToggleScreen() {
	}

	public static Screen create(final Screen parent) {
		Component title = Component.literal("Resource Pack Optimizer");
		if (RpoSettings.mixinsDisabled()) {
			return new ConfirmScreen(
				change -> Minecraft.getInstance().gui.setScreen(parent),
				title,
				Component.literal("It is turned off for this session by -Drpo.disable=true."),
				Component.literal("Done"),
				Component.literal("Back")
			);
		}

		boolean on = RpoSettings.requested();
		return new ConfirmScreen(
			change -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (change) {
					RpoSettings.setEnabled(!on);
					RpoSettings.request(!on);
					minecraft.setScreen(create(parent));
					minecraft.reloadResourcePacks();
				} else {
					minecraft.setScreen(parent);
				}
			},
			title,
			Component.literal(on ? "It is turned on." : "It is turned off. Resources load like vanilla."),
			Component.literal(on ? "Turn off" : "Turn on"),
			Component.literal("Done")
		);
	}
}
