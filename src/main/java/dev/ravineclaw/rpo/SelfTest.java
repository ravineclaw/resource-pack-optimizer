package dev.ravineclaw.rpo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.spongepowered.asm.mixin.MixinEnvironment;

public final class SelfTest {
	private static final int RELOADS = 3;
	private static final String WORLD = "rpo-selftest";
	private static final String SOUND_PACK = "file/nosounds.zip";
	private static final String TEXTURE_PACK = "file/rpo-test.zip";

	private SelfTest() {
	}

	public static void startIfRequested() {
		if (!Boolean.getBoolean("rpo.selftest")) {
			return;
		}

		Configurator.setLevel("net.minecraft.server.packs.resources.ReloadableResourceManager", Level.DEBUG);
		Thread thread = new Thread(SelfTest::run, "RPO self-test");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run() {
		try {
			long start = System.nanoTime();
			Minecraft minecraft = waitFor(Minecraft::getInstance);
			waitUntil(minecraft, () -> minecraft.getOverlay() == null && minecraft.screen instanceof TitleScreen);
			log("startup until title screen: {} ms", (System.nanoTime() - start) / 1_000_000L);

			if (!Boolean.getBoolean("rpo.disable")) {
				CompletableFuture.runAsync(() -> MixinEnvironment.getCurrentEnvironment().audit(), minecraft).join();
				log("mixin audit passed");
			}

			for (int i = 0; i < RELOADS; i++) {
				reload(minecraft, "title reload " + (i + 1), false);
			}

			if (Boolean.getBoolean("rpo.selftest.world")) {
				inWorld(minecraft);
			}

			String tag = System.getProperty("rpo.selftest.tag", "run");
			Path dumpDir = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(tag);
			CompletableFuture.runAsync(() -> minecraft.getTextureManager().dumpAllSheets(dumpDir), minecraft).join();
			log("dumped atlases to {}", dumpDir);

			if (minecraft.level != null) {
				CompletableFuture.runAsync(() -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), minecraft).join();
				waitUntil(minecraft, () -> minecraft.level == null);
			}

			log("done");
			minecraft.execute(minecraft::stop);
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.error("[selftest] failed", t);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				minecraft.execute(minecraft::stop);
			}
		}
	}

	private static void inWorld(final Minecraft minecraft) throws InterruptedException {
		long start = System.nanoTime();
		CompletableFuture.runAsync(() -> {
			if (minecraft.getLevelSource().levelExists(WORLD)) {
				minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.setScreen(new TitleScreen()));
			} else {
				LevelSettings settings = new LevelSettings(
					WORLD, GameType.SPECTATOR, false, Difficulty.NORMAL, true, new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT
				);
				minecraft.createWorldOpenFlows()
					.createFreshLevel(WORLD, settings, SelectWorldScreen.TEST_OPTIONS, WorldPresets::createNormalWorldDimensions, new TitleScreen());
			}
		}, minecraft).join();
		waitUntil(minecraft, () -> minecraft.level != null && minecraft.player != null && minecraft.screen == null && minecraft.getOverlay() == null);
		waitForChunks(minecraft, 180_000L);
		log("world open with all chunks built: {} ms ({} sections)", (System.nanoTime() - start) / 1_000_000L, sections(minecraft));

		for (int i = 0; i < 2; i++) {
			reload(minecraft, "world reload " + (i + 1), true);
		}

		togglePack(minecraft, SOUND_PACK);
		togglePack(minecraft, TEXTURE_PACK);

		int mipmaps = minecraft.options.mipmapLevels().get();
		setMipmaps(minecraft, mipmaps == 4 ? 2 : 4);
		reload(minecraft, "world mipmap change", true);
		setMipmaps(minecraft, mipmaps);
		reload(minecraft, "world mipmap restore", true);

		reload(minecraft, "world final reload", true);
	}

	private static void togglePack(final Minecraft minecraft, final String packId) throws InterruptedException {
		PackRepository repository = minecraft.getResourcePackRepository();
		List<String> selected = CompletableFuture.supplyAsync(() -> new ArrayList<>(repository.getSelectedIds()), minecraft).join();
		if (!selected.contains(packId)) {
			log("pack {} is not selected, skipping its toggle test", packId);
			return;
		}

		List<String> without = new ArrayList<>(selected);
		without.remove(packId);
		CompletableFuture.runAsync(() -> repository.setSelected(without), minecraft).join();
		reload(minecraft, "world without " + packId, true);
		CompletableFuture.runAsync(() -> repository.setSelected(selected), minecraft).join();
		reload(minecraft, "world with " + packId, true);
	}

	private static void setMipmaps(final Minecraft minecraft, final int levels) {
		CompletableFuture.runAsync(() -> {
			minecraft.options.mipmapLevels().set(levels);
			minecraft.updateMaxMipLevel(levels);
		}, minecraft).join();
	}

	private static void reload(final Minecraft minecraft, final String label, final boolean waitForChunks) throws InterruptedException {
		long start = System.nanoTime();
		CompletableFuture.runAsync(minecraft::reloadResourcePacks, minecraft).join();
		waitUntil(minecraft, () -> minecraft.getOverlay() == null);
		long overlay = (System.nanoTime() - start) / 1_000_000L;
		ReloadTimeline.log(label);
		if (!waitForChunks) {
			log("{}: until overlay gone {} ms", label, overlay);
			return;
		}

		waitForChunks(minecraft, 60_000L);
		log("{}: until overlay gone {} ms, until all chunks built {} ms", label, overlay, (System.nanoTime() - start) / 1_000_000L);
	}

	private static void waitForChunks(final Minecraft minecraft, final long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		int quietChecks = 0;
		while (quietChecks < 5 && System.currentTimeMillis() < deadline) {
			boolean done = CompletableFuture.supplyAsync(
				() -> minecraft.levelRenderer.countRenderedSections() > 0 && minecraft.levelRenderer.hasRenderedAllSections(), minecraft
			).join();
			quietChecks = done ? quietChecks + 1 : 0;
			Thread.sleep(done ? 2L : 5L);
		}
	}

	private static int sections(final Minecraft minecraft) {
		return CompletableFuture.supplyAsync(minecraft.levelRenderer::countRenderedSections, minecraft).join();
	}

	private static void log(final String message, final Object... args) {
		ResourcePackOptimizer.LOGGER.info("[selftest] " + message, args);
	}

	private static <T> T waitFor(final Supplier<T> supplier) throws InterruptedException {
		T value;
		while ((value = supplier.get()) == null) {
			Thread.sleep(10L);
		}

		return value;
	}

	private static void waitUntil(final Minecraft minecraft, final Supplier<Boolean> condition) throws InterruptedException {
		while (!CompletableFuture.supplyAsync(condition, minecraft).join()) {
			Thread.sleep(5L);
		}
	}
}
