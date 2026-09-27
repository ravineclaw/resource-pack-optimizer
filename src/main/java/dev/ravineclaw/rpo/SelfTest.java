package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
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
			waitUntil(minecraft, () -> minecraft.gui.overlay() == null && BackgroundReload.current() == null && minecraft.gui.screen() instanceof TitleScreen);
			log("startup until title screen: {} ms", (System.nanoTime() - start) / 1_000_000L);
			ReloadTimeline.log("startup reload");

			if (!RpoSettings.mixinsDisabled()) {
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
			if (!RpoSettings.mixinsDisabled()) {
				boolean inWorld = minecraft.level != null;
				RpoSettings.request(false);
				reload(minecraft, "turned off", inWorld);
				Path offDir = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(tag + "-off");
				CompletableFuture.runAsync(() -> minecraft.getTextureManager().dumpAllSheets(offDir), minecraft).join();
				log("dumped atlases with the mod turned off to {}", offDir);
				RpoSettings.request(true);
				reload(minecraft, "turned on again", inWorld);
				reload(minecraft, "reload after turning on", inWorld);
			}

			Path dumpDir = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(tag);
			CompletableFuture.runAsync(() -> minecraft.getTextureManager().dumpAllSheets(dumpDir), minecraft).join();
			log("dumped atlases to {}", dumpDir);

			if (minecraft.level != null) {
				CompletableFuture.runAsync(minecraft::disconnectWithSavingScreen, minecraft).join();
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

	private static void inWorld(final Minecraft minecraft) throws Exception {
		long start = System.nanoTime();
		CompletableFuture.runAsync(() -> {
			if (minecraft.getLevelSource().levelExists(WORLD)) {
				minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
			} else {
				LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
				minecraft.createWorldOpenFlows()
					.createFreshLevel(WORLD, settings, SelectWorldScreen.TEST_OPTIONS, WorldPresets::createNormalWorldDimensions, new TitleScreen());
			}
		}, minecraft).join();
		waitUntil(minecraft, () -> minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null && minecraft.gui.overlay() == null && BackgroundReload.current() == null);
		waitForChunks(minecraft, 180_000L);
		log("world open with all chunks built: {} ms ({} sections)", (System.nanoTime() - start) / 1_000_000L, sections(minecraft));

		for (int i = 0; i < 2; i++) {
			reload(minecraft, "world reload " + (i + 1), true);
		}

		togglePack(minecraft, SOUND_PACK);
		togglePack(minecraft, TEXTURE_PACK);
		folderProbe(minecraft);

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

	private static void folderProbe(final Minecraft minecraft) throws Exception {
		List<String> selected = CompletableFuture.supplyAsync(() -> new ArrayList<>(minecraft.getResourcePackRepository().getSelectedIds()), minecraft).join();
		Path folder = null;
		for (String id : selected) {
			Path candidate = minecraft.getResourcePackDirectory().resolve(id.substring(id.indexOf('/') + 1));
			if (id.startsWith("file/") && Files.isDirectory(candidate)) {
				folder = candidate;
				break;
			}
		}

		if (folder == null) {
			log("no folder pack selected, skipping folder probe");
			return;
		}

		Path probe = folder.resolve("assets/minecraft/textures/block/rpo_selftest_probe.png");
		Files.createDirectories(probe.getParent());
		try (NativeImage image = new NativeImage(16, 16, false)) {
			image.fillRect(0, 0, 16, 16, 0xFF00FF00);
			image.writeToFile(probe);
		}

		try {
			reload(minecraft, "world folder probe added", true);
			log("folder probe added: block atlas rebuilt = {} (expected true)", !ReloadChanges.isUnchanged(ReloadChanges.BLOCK_ATLAS));
		} finally {
			Files.deleteIfExists(probe);
		}

		reload(minecraft, "world folder probe removed", true);
		log("folder probe removed: block atlas rebuilt = {} (expected true)", !ReloadChanges.isUnchanged(ReloadChanges.BLOCK_ATLAS));
		reload(minecraft, "world folder probe unchanged", true);
		log("folder probe unchanged: block atlas rebuilt = {} (expected false)", !ReloadChanges.isUnchanged(ReloadChanges.BLOCK_ATLAS));
	}

	private static void setMipmaps(final Minecraft minecraft, final int levels) {
		CompletableFuture.runAsync(() -> {
			minecraft.options.mipmapLevels().set(levels);
			minecraft.updateMaxMipLevel(levels);
		}, minecraft).join();
	}

	private static void reload(final Minecraft minecraft, final String label, final boolean waitForChunks) throws InterruptedException {
		CompletableFuture.runAsync(ReloadTimeline::resetFrames, minecraft).join();
		long start = System.nanoTime();
		boolean blocking = CompletableFuture.supplyAsync(() -> {
			minecraft.reloadResourcePacks();
			return minecraft.gui.overlay() != null;
		}, minecraft).join();
		if (blocking) {
			log("{}: reload shows a blocking overlay", label);
		}

		waitUntil(minecraft, () -> minecraft.gui.overlay() == null && BackgroundReload.current() == null);
		long overlay = (System.nanoTime() - start) / 1_000_000L;
		long frame = ReloadTimeline.longestFrameMillis();
		ReloadTimeline.log(label);
		if (!waitForChunks) {
			log("{}: until overlay gone {} ms, longest frame {} ms", label, overlay, frame);
			return;
		}

		waitForChunks(minecraft, 60_000L);
		log("{}: until overlay gone {} ms, until all chunks built {} ms, longest frame {} ms", label, overlay, (System.nanoTime() - start) / 1_000_000L, frame);
	}

	private static void waitForChunks(final Minecraft minecraft, final long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		int quietChecks = 0;
		while (quietChecks < 5 && System.currentTimeMillis() < deadline) {
			boolean done = CompletableFuture.supplyAsync(
				() -> minecraft.levelExtractor.countRenderedSections() > 0 && minecraft.levelRenderer.hasRenderedAllSections(), minecraft
			).join();
			quietChecks = done ? quietChecks + 1 : 0;
			Thread.sleep(done ? 2L : 5L);
		}
	}

	private static int sections(final Minecraft minecraft) {
		return CompletableFuture.supplyAsync(minecraft.levelExtractor::countRenderedSections, minecraft).join();
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
