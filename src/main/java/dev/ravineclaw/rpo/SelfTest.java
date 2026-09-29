package dev.ravineclaw.rpo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ravineclaw.rpo.mixin.TextureAtlasAccessor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.jspecify.annotations.Nullable;
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
			waitUntil(minecraft, () -> minecraft.getOverlay() == null && BackgroundReload.current() == null && minecraft.screen instanceof TitleScreen);
			log("startup until title screen: {} ms", (System.nanoTime() - start) / 1_000_000L);
			ReloadTimeline.log("startup reload");
			log("shaders: {}", SpirvCache.stats());

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
				screenshot(minecraft, tag + "-world-off");
				Path offDir = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(tag + "-off");
				dump(minecraft, offDir);
				log("dumped atlases with the mod turned off to {}", offDir);
				RpoSettings.request(true);
				reload(minecraft, "turned on again", inWorld);
				reload(minecraft, "reload after turning on", inWorld);
			}

			Path dumpDir = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(tag);
			dump(minecraft, dumpDir);
			log("dumped atlases to {}", dumpDir);

			if (minecraft.level != null) {
				CompletableFuture.runAsync(() -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), minecraft).join();
				waitUntil(minecraft, () -> minecraft.level == null);
			}

			SpriteDiskCache.flush();
			SpirvDiskCache.flush();
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
				minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.setScreen(new TitleScreen()));
			} else {
				LevelSettings settings = new LevelSettings(
					WORLD, GameType.SPECTATOR, false, Difficulty.NORMAL, true, new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT
				);
				minecraft.createWorldOpenFlows()
					.createFreshLevel(WORLD, settings, SelectWorldScreen.TEST_OPTIONS, WorldPresets::createNormalWorldDimensions, new TitleScreen());
			}
		}, minecraft).join();
		waitUntil(minecraft, () -> minecraft.level != null && minecraft.player != null && minecraft.screen == null && minecraft.getOverlay() == null && BackgroundReload.current() == null);
		waitForChunks(minecraft, 180_000L);
		log("world open with all chunks built: {} ms ({} sections)", (System.nanoTime() - start) / 1_000_000L, sections(minecraft));

		SoundInstance record = CompletableFuture.supplyAsync(() -> {
			SoundInstance instance = SimpleSoundInstance.forMusic(SoundEvents.MUSIC_DISC_CAT.value());
			minecraft.getSoundManager().play(instance);
			return instance;
		}, minecraft).join();
		long soundDeadline = System.currentTimeMillis() + 3_000L;
		while (!CompletableFuture.supplyAsync(() -> minecraft.getSoundManager().isActive(record), minecraft).join() && System.currentTimeMillis() < soundDeadline) {
			Thread.sleep(10L);
		}

		boolean playing = CompletableFuture.supplyAsync(() -> minecraft.getSoundManager().isActive(record), minecraft).join();
		for (int i = 0; i < 2; i++) {
			reload(minecraft, "world reload " + (i + 1), true);
		}

		if (playing) {
			log(
				"record still playing after two unchanged reloads: {} (expected true, sounds kept: {})",
				CompletableFuture.supplyAsync(() -> minecraft.getSoundManager().isActive(record), minecraft).join(),
				ReloadChanges.isUnchanged("sounds")
			);
		} else {
			log("record didn't start (no sound device), skipping the sound check");
		}

		CompletableFuture.runAsync(() -> minecraft.getSoundManager().stop(record), minecraft).join();

		togglePack(minecraft, SOUND_PACK);
		togglePack(minecraft, TEXTURE_PACK);
		checkSpriteFinders(minecraft);
		folderProbe(minecraft);
		checkSpriteFinders(minecraft);

		int mipmaps = minecraft.options.mipmapLevels().get();
		setMipmaps(minecraft, mipmaps == 4 ? 2 : 4);
		reload(minecraft, "world mipmap change", true, System.getProperty("rpo.selftest.tag", "run") + "-world-mip-switch", TerrainHandoff::active);
		setMipmaps(minecraft, mipmaps);
		reload(minecraft, "world mipmap restore", true);

		moveDuringHandoff(minecraft, mipmaps);
		reload(minecraft, "world final reload", true);
		screenshot(minecraft, System.getProperty("rpo.selftest.tag", "run") + "-world");
	}

	private static void checkSpriteFinders(final Minecraft minecraft) {
		CompletableFuture.runAsync(() -> {
			minecraft.getAtlasManager().forEach((id, atlas) -> {
				try {
					Method finder = atlas.getClass().getMethod("spriteFinder");
					checkSpriteFinder("Fabric", id, atlas, finder.invoke(atlas));
				} catch (NoSuchMethodException e) {
					return;
				} catch (ReflectiveOperationException | RuntimeException e) {
					ResourcePackOptimizer.LOGGER.error("[selftest] Fabric sprite finder of {} failed", id, e);
				}
			});

			try {
				Class<?> cache = Class.forName("net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache");
				checkSpriteFinder("Sodium", AtlasIds.BLOCKS, minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS), cache.getMethod("forBlockAtlas").invoke(null));
				checkSpriteFinder("Sodium", AtlasIds.ITEMS, minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.ITEMS), cache.getMethod("forItemAtlas").invoke(null));
			} catch (ClassNotFoundException e) {
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.error("[selftest] Sodium sprite finder failed", e);
			}
		}, minecraft).join();
	}

	private static void checkSpriteFinder(final String mod, final Identifier id, final TextureAtlas atlas, final Object finder) throws ReflectiveOperationException {
		Method find = finder.getClass().getMethod("find", float.class, float.class);
		find.setAccessible(true);
		int checked = 0;
		int wrong = 0;
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor)atlas).rpo$getTexturesByName().values()) {
			checked++;
			if (find.invoke(finder, (sprite.getU0() + sprite.getU1()) / 2.0F, (sprite.getV0() + sprite.getV1()) / 2.0F) != sprite) {
				wrong++;
			}
		}

		log("{} sprite finder of {}: {} sprites checked, {} wrong", mod, id, checked, wrong);
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
		reload(
			minecraft,
			"world without " + packId,
			true,
			packId.equals(TEXTURE_PACK) ? System.getProperty("rpo.selftest.tag", "run") + "-world-swapped" : null,
			() -> TerrainHandoff.offered() && !TerrainHandoff.active()
		);
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

	private static void moveDuringHandoff(final Minecraft minecraft, final int mipmaps) throws InterruptedException {
		Vec3 home = CompletableFuture.supplyAsync(() -> minecraft.player != null ? minecraft.player.position() : null, minecraft).join();
		if (home == null) {
			return;
		}

		String handoff = TerrainHandoff.lastResult();
		setMipmaps(minecraft, mipmaps == 4 ? 2 : 4);
		CompletableFuture.runAsync(minecraft::reloadResourcePacks, minecraft).join();
		long start = System.nanoTime();
		boolean moved = false;
		while (!moved && System.nanoTime() - start < 10_000_000_000L) {
			moved = CompletableFuture.supplyAsync(() -> {
				if (!TerrainHandoff.active() || minecraft.player == null) {
					return false;
				}

				minecraft.player.setPos(home.x + 160.0, home.y, home.z + 160.0);
				return true;
			}, minecraft).join();
			if (!moved) {
				Thread.sleep(1L);
			}
		}

		waitUntil(minecraft, () -> minecraft.getOverlay() == null && BackgroundReload.current() == null);
		waitForChunks(minecraft, 60_000L);
		long deadline = System.currentTimeMillis() + 10_000L;
		while (TerrainHandoff.active() && System.currentTimeMillis() < deadline) {
			Thread.sleep(5L);
		}

		String result = TerrainHandoff.lastResult();
		log("world moved 160 blocks during the chunk handoff: moved = {}, chunk handoff {}", moved, result != null && !result.equals(handoff) ? result : "none");
		CompletableFuture.runAsync(() -> {
			if (minecraft.player != null) {
				minecraft.player.setPos(home.x, home.y, home.z);
			}
		}, minecraft).join();
		setMipmaps(minecraft, mipmaps);
		reload(minecraft, "world mipmap restore after moving", true);
	}

	private static void setMipmaps(final Minecraft minecraft, final int levels) {
		CompletableFuture.runAsync(() -> {
			minecraft.options.mipmapLevels().set(levels);
			minecraft.updateMaxMipLevel(levels);
		}, minecraft).join();
	}

	private static void screenshot(final Minecraft minecraft, final String name) throws InterruptedException {
		if (minecraft.level == null) {
			return;
		}

		Thread.sleep(1000L);
		CompletableFuture.supplyAsync(() -> screenshotNow(minecraft, name), minecraft).join().join();
	}

	private static CompletableFuture<Void> screenshotNow(final Minecraft minecraft, final String name) {
		Path file = minecraft.gameDirectory.toPath().resolve("rpo-dump").resolve(name + ".png");
		CompletableFuture<Void> written = new CompletableFuture<>();
		Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
			try (image) {
				Files.createDirectories(file.getParent());
				image.writeToFile(file);
				log("screenshot {}", file);
			} catch (Exception e) {
				ResourcePackOptimizer.LOGGER.error("[selftest] screenshot failed", e);
			} finally {
				written.complete(null);
			}
		});
		return written;
	}

	private static void dump(final Minecraft minecraft, final Path directory) {
		CompletableFuture<Void> written = new CompletableFuture<>();
		CompletableFuture.runAsync(() -> {
			minecraft.getTextureManager().dumpAllSheets(directory);
			RenderSystem.queueFencedTask(() -> written.complete(null));
		}, minecraft).join();
		written.join();
	}

	private static void reload(final Minecraft minecraft, final String label, final boolean waitForChunks) throws InterruptedException {
		reload(minecraft, label, waitForChunks, null, TerrainHandoff::active);
	}

	private static void reload(
		final Minecraft minecraft, final String label, final boolean waitForChunks, final @Nullable String shot, final BooleanSupplier state
	) throws InterruptedException {
		CompletableFuture.runAsync(ReloadTimeline::resetFrames, minecraft).join();
		String handoff = TerrainHandoff.lastResult();
		long start = System.nanoTime();
		boolean blocking = CompletableFuture.supplyAsync(() -> {
			minecraft.reloadResourcePacks();
			return minecraft.getOverlay() != null;
		}, minecraft).join();
		if (blocking) {
			log("{}: reload shows a blocking overlay", label);
		}

		CompletableFuture<Void> shotWritten = null;
		while (shot != null) {
			shotWritten = CompletableFuture.supplyAsync(() -> state.getAsBoolean() ? screenshotNow(minecraft, shot) : null, minecraft).join();
			if (shotWritten != null || System.nanoTime() - start > 10_000_000_000L) {
				break;
			}

			Thread.sleep(1L);
		}

		if (shot != null) {
			log("{}: screenshot {} taken: {}", label, shot, shotWritten != null);
		}

		waitUntil(minecraft, () -> minecraft.getOverlay() == null && BackgroundReload.current() == null);
		long overlay = (System.nanoTime() - start) / 1_000_000L;
		if (shotWritten != null) {
			shotWritten.join();
		}

		long frame = ReloadTimeline.longestFrameMillis();
		ReloadTimeline.log(label);
		if (!waitForChunks) {
			log("{}: until overlay gone {} ms, longest frame {} ms", label, overlay, frame);
			return;
		}

		waitForChunks(minecraft, 60_000L);
		log("{}: until overlay gone {} ms, until all chunks built {} ms, longest frame {} ms", label, overlay, (System.nanoTime() - start) / 1_000_000L, frame);
		long deadline = System.currentTimeMillis() + 10_000L;
		while (TerrainHandoff.active() && System.currentTimeMillis() < deadline) {
			Thread.sleep(5L);
		}

		String result = TerrainHandoff.lastResult();
		if (result != null && !result.equals(handoff)) {
			log("{}: chunk handoff {}", label, result);
		}

		if (shotWritten != null) {
			String after = shot + "-after";
			CompletableFuture.supplyAsync(() -> screenshotNow(minecraft, after), minecraft).join().join();
			log("{}: {} differs from the rebuilt frame in {}% of pixels (expected < 1)", label, shot, differingPercent(minecraft, shot, after));
		}
	}

	private static String differingPercent(final Minecraft minecraft, final String first, final String second) {
		Path directory = minecraft.gameDirectory.toPath().resolve("rpo-dump");
		try (NativeImage a = NativeImage.read(Files.readAllBytes(directory.resolve(first + ".png")));
			NativeImage b = NativeImage.read(Files.readAllBytes(directory.resolve(second + ".png")))) {
			if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
				return "100 (size differs)";
			}

			long differing = 0;
			int top = a.getHeight() / 20;
			for (int y = top; y < a.getHeight(); y++) {
				for (int x = 0; x < a.getWidth(); x++) {
					int p = a.getPixel(x, y);
					int q = b.getPixel(x, y);
					int delta = Math.abs((p & 0xFF) - (q & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF));
					if (delta > 30) {
						differing++;
					}
				}
			}

			return String.format(Locale.ROOT, "%.3f", differing * 100.0 / ((long)a.getWidth() * (a.getHeight() - top)));
		} catch (Exception e) {
			return "unknown (" + e + ")";
		}
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
