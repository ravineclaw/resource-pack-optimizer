package dev.ravineclaw.rpo;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class Benchmark {
	private static final String WORLD = "rpo-showcase";
	private static final String SERVER_NAME = "RPO Test Server";
	private static final boolean SHOW = Boolean.getBoolean("rpo.bench.show");
	private static final int RUNS = SHOW ? 1 : Integer.getInteger("rpo.bench.runs", 5);
	private static final String PACK = System.getProperty("rpo.bench.pack", "file/Prime 32x.zip");
	private static final String SERVER = System.getProperty("rpo.bench.server", "127.0.0.1:25599");
	private static final boolean REJOIN = Boolean.getBoolean("rpo.bench.rejoin");
	private static final float PAN_DEGREES_PER_SECOND = 9.0F;
	private static final AtomicBoolean QUEUED = new AtomicBoolean();
	private static volatile boolean running = true;
	private static volatile boolean panning;
	private static volatile float baseYaw;
	private static volatile float basePitch = 12.0F;
	private static volatile boolean fixedPitch;
	private static volatile double panSeconds;
	private static volatile long lastFrame;
	private static volatile long longestFrame;
	private static volatile long queuedAt;
	private static long origin;

	private Benchmark() {
	}

	public static void startIfRequested() {
		String scenario = System.getProperty("rpo.bench");
		if (scenario == null) {
			return;
		}

		Thread thread = new Thread(() -> run(scenario), "RPO benchmark");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run(final String scenario) {
		Minecraft minecraft = null;
		try {
			minecraft = waitFor(Minecraft::getInstance);
			Minecraft mc = minecraft;
			waitUntil(mc, () -> idle(mc) && mc.screen instanceof TitleScreen);
			onMain(mc, () -> {
				mc.options.pauseOnLostFocus = false;
				mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
				if (SHOW) {
					mc.getWindow().setWindowed(1280, 720);
				}
			});
			startFrameMeter(mc);
			ensurePack(mc, false);
			Thread.sleep(1000L);
			log("ready");

			switch (scenario) {
				case "menu" -> menu(mc);
				case "world" -> world(mc);
				case "server" -> server(mc);
				default -> log("unknown scenario {}", scenario);
			}

			if (mc.level != null && !SHOW) {
				onMain(mc, () -> PauseScreen.disconnectFromWorld(mc, ClientLevel.DEFAULT_QUIT_MESSAGE));
				waitUntil(mc, () -> mc.level == null && idle(mc));
			}

			ensurePack(mc, false);
			log("done");
		} catch (Throwable t) {
			ResourcePackOptimizer.LOGGER.error("[bench] failed", t);
		}

		running = false;
		if (minecraft != null) {
			minecraft.execute(minecraft::stop);
		}
	}

	private static void menu(final Minecraft mc) throws Exception {
		waitForGo(mc);
		for (int i = 0; i < RUNS; i++) {
			Measure enable = switchPack(mc, true, true);
			log("menu enable: {} ms, longest frame {} ms", enable.doneMillis, enable.longestFrame);
			if (SHOW) {
				Thread.sleep(3000L);
				return;
			}

			Thread.sleep(500L);
			Measure disable = switchPack(mc, false, true);
			log("menu disable: {} ms, longest frame {} ms", disable.doneMillis, disable.longestFrame);
			Thread.sleep(500L);
		}
	}

	private static void world(final Minecraft mc) throws Exception {
		onMain(mc, () -> {
			if (mc.getLevelSource().levelExists(WORLD)) {
				mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
			} else {
				LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows()
					.createFreshLevel(WORLD, settings, SelectWorldScreen.TEST_OPTIONS, WorldPresets::createNormalWorldDimensions, new TitleScreen());
			}
		});
		waitUntil(mc, () -> mc.level != null && mc.player != null && mc.screen == null && idle(mc));
		setUpWorld(mc);
		waitForChunks(mc, 120_000L);
		if (Boolean.getBoolean("rpo.bench.scout")) {
			scout(mc);
			return;
		}

		onMain(mc, () -> mc.gui.getChat().clearMessages(false));
		startPanning(mc);
		waitForGo(mc);
		Thread.sleep(SHOW ? 3000L : 1000L);
		for (int i = 0; i < RUNS; i++) {
			Measure enable = switchPack(mc, true, false);
			long chunks = chunksAfter(mc, enable);
			log("world enable: {} ms, blocked {} ms, chunks {} ms, longest frame {} ms", enable.doneMillis, enable.blockedMillis, chunks, enable.longestFrame);
			if (SHOW) {
				Thread.sleep(6000L);
				return;
			}

			Thread.sleep(1000L);
			Measure disable = switchPack(mc, false, false);
			chunks = chunksAfter(mc, disable);
			log("world disable: {} ms, blocked {} ms, chunks {} ms, longest frame {} ms", disable.doneMillis, disable.blockedMillis, chunks, disable.longestFrame);
			Thread.sleep(1000L);
		}
	}

	private static void server(final Minecraft mc) throws Exception {
		onMain(mc, () -> {
			ServerList servers = new ServerList(mc);
			servers.load();
			ServerData data = servers.get(SERVER);
			if (data == null) {
				data = new ServerData(SERVER_NAME, SERVER, ServerData.Type.OTHER);
				servers.add(data, false);
			}

			data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
			servers.save();
		});
		if (REJOIN) {
			TitleScreen title = new TitleScreen();
			onMain(mc, () -> mc.setScreen(new JoinMultiplayerScreen(title)));
			Thread.sleep(500L);
			measure(mc, () -> {
				JoinMultiplayerScreen screen = (JoinMultiplayerScreen) mc.screen;
				ServerData data = screen.getServers().get(SERVER);
				ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(data.ip), data, false, null);
			}, () -> mc.level != null && mc.player != null && mc.screen == null && idle(mc) && hasServerPack(mc));
			Thread.sleep(1500L);
			measure(mc, () -> PauseScreen.disconnectFromWorld(mc, ClientLevel.DEFAULT_QUIT_MESSAGE),
				() -> mc.level == null && idle(mc) && !hasServerPack(mc) && mc.screen instanceof JoinMultiplayerScreen);
			onMain(mc, () -> mc.setScreen(title));
			Thread.sleep(1000L);
		}

		waitForGo(mc);
		for (int i = 0; i < RUNS; i++) {
			TitleScreen title = new TitleScreen();
			onMain(mc, () -> mc.setScreen(new JoinMultiplayerScreen(title)));
			event("multiplayer screen");
			Thread.sleep(SHOW ? 1500L : 500L);
			Measure join = measure(mc, () -> {
				JoinMultiplayerScreen screen = (JoinMultiplayerScreen) mc.screen;
				ServerData data = screen.getServers().get(SERVER);
				ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(data.ip), data, false, null);
			}, () -> mc.level != null && mc.player != null && mc.screen == null && idle(mc) && hasServerPack(mc));
			log("server join: {} ms, longest frame {} ms", join.doneMillis, join.longestFrame);
			if (SHOW) {
				onMain(mc, () -> mc.gui.getChat().clearMessages(false));
				startPanning(mc);
				Thread.sleep(4000L);
				return;
			}

			Thread.sleep(1500L);
			Measure leave = measure(mc, () -> PauseScreen.disconnectFromWorld(mc, ClientLevel.DEFAULT_QUIT_MESSAGE),
				() -> mc.level == null && idle(mc) && !hasServerPack(mc) && mc.screen instanceof JoinMultiplayerScreen);
			log("server leave: {} ms, longest frame {} ms", leave.doneMillis, leave.longestFrame);
			onMain(mc, () -> mc.setScreen(title));
			Thread.sleep(1000L);
		}
	}

	private static Measure switchPack(final Minecraft mc, final boolean enable, final boolean menu) throws Exception {
		PackSelectionScreen[] holder = new PackSelectionScreen[1];
		onMain(mc, () -> {
			Screen back = menu ? mc.screen : null;
			holder[0] = new PackSelectionScreen(mc.getResourcePackRepository(), repository -> {
				mc.options.updateResourcePacks(repository);
				mc.setScreen(back);
			}, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title"));
			mc.setScreen(holder[0]);
		});
		event("pack screen");
		Thread.sleep(SHOW ? 1500L : 200L);
		onMain(mc, () -> {
			PackSelectionModel model = model(holder[0]);
			(enable ? model.getUnselected() : model.getSelected())
				.filter(entry -> entry.getId().equals(PACK))
				.findFirst()
				.ifPresentOrElse(entry -> {
					if (enable) {
						entry.select();
					} else {
						entry.unselect();
					}
				}, () -> {
					throw new IllegalStateException("pack " + PACK + " not found");
				});
		});
		Thread.sleep(SHOW ? 1200L : 100L);
		return measure(mc, holder[0]::onClose, () -> idle(mc));
	}

	private static Measure measure(final Minecraft mc, final Runnable action, final Supplier<Boolean> done) throws Exception {
		longestFrame = 0L;
		long start = System.nanoTime();
		event("trigger");
		onMain(mc, action);
		long blocked = -1L;
		long deadline = start + 120_000_000_000L;
		while (true) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("timed out waiting for the step to finish");
			}


			boolean[] state = CompletableFuture.supplyAsync(() -> new boolean[] {done.get(), mc.getOverlay() == null && mc.screen == null}, mc).join();
			if (blocked < 0L && state[1]) {
				blocked = (System.nanoTime() - start) / 1_000_000L;
			}

			if (state[0]) {
				break;
			}

			Thread.sleep(2L);
		}

		long doneMillis = (System.nanoTime() - start) / 1_000_000L;
		event("done");
		return new Measure(start, doneMillis, blocked < 0L ? doneMillis : blocked, longestFrame / 1_000_000L);
	}

	private static long chunksAfter(final Minecraft mc, final Measure measure) throws InterruptedException {
		waitForChunks(mc, 60_000L);
		return (System.nanoTime() - measure.start) / 1_000_000L;
	}

	private static void ensurePack(final Minecraft mc, final boolean selected) throws Exception {
		boolean changed = CompletableFuture.supplyAsync(() -> {
			PackRepository repository = mc.getResourcePackRepository();
			List<String> ids = new ArrayList<>(repository.getSelectedIds());
			if (ids.contains(PACK) == selected) {
				return false;
			}

			if (selected) {
				ids.add(PACK);
			} else {
				ids.remove(PACK);
			}

			repository.setSelected(ids);
			mc.options.updateResourcePacks(repository);
			return true;
		}, mc).join();
		if (changed) {
			waitUntil(mc, () -> idle(mc));
		}
	}

	private static void setUpWorld(final Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}

		String pos = System.getProperty("rpo.bench.pos");
		if (pos != null) {
			basePitch = Float.parseFloat(pos.split(",")[4]);
			fixedPitch = true;
		}

		CompletableFuture.runAsync(() -> {
			command(server, "gamerule send_command_feedback false");
			command(server, "gamerule show_advancement_messages false");
			command(server, "time set 6000");
			command(server, "weather clear");
			if (pos != null) {
				String[] p = pos.split(",");
				command(server, "tp @a " + p[0] + " " + p[1] + " " + p[2] + " " + p[3] + " " + p[4]);
			}

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				ItemStack[] items = {
					new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.GOLDEN_APPLE, 16),
					new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 64),
					new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.COOKED_BEEF, 32), new ItemStack(Items.DIAMOND_PICKAXE)
				};
				for (int i = 0; i < items.length; i++) {
					player.getInventory().setItem(i, items[i]);
				}

				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
		}, server).join();
	}

	private static void scout(final Minecraft mc) throws Exception {
		MinecraftServer server = mc.getSingleplayerServer();
		int spacing = Integer.getInteger("rpo.bench.scout.spacing", 256);
		int index = 0;
		for (int gx = -2; gx <= 2; gx++) {
			for (int gz = -2; gz <= 2; gz++) {
				int x = gx * spacing;
				int z = gz * spacing;
				CompletableFuture.runAsync(() -> command(server, "tp @a " + x + " 200 " + z + " 45 25"), server).join();
				Thread.sleep(2500L);
				int height = CompletableFuture.supplyAsync(() -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), mc).join();
				int y = height + 12;
				CompletableFuture.runAsync(() -> command(server, "tp @a " + x + " " + y + " " + z + " 45 25"), server).join();
				Thread.sleep(1500L);
				waitForChunks(mc, 20_000L);
				Thread.sleep(500L);
				int shot = index++;
				onMain(mc, () -> Screenshot.grab(mc.gameDirectory, "scout-" + shot + ".png", mc.getMainRenderTarget(), 1, message -> {
				}));
				log("scout {}: {},{},{},45,25", shot, x, y, z);
			}
		}
	}

	private static void command(final MinecraftServer server, final String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static void startPanning(final Minecraft mc) {
		onMain(mc, () -> {
			LocalPlayer player = mc.player;
			if (player != null) {
				baseYaw = player.getYRot();
				if (!fixedPitch) {
					basePitch = player.getXRot();
				}
			}

			panSeconds = 0.0;
			panning = true;
		});
	}

	private static void startFrameMeter(final Minecraft mc) {
		Thread thread = new Thread(() -> {
			while (running) {
				if (QUEUED.get() && System.nanoTime() - queuedAt > 1_000_000_000L) {
					QUEUED.set(false);
				}

				if (QUEUED.compareAndSet(false, true)) {
					queuedAt = System.nanoTime();
					mc.execute(() -> frame(mc));
				}

				try {
					Thread.sleep(1L);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "RPO benchmark frame meter");
		thread.setDaemon(true);
		thread.start();
	}

	private static void frame(final Minecraft mc) {
		QUEUED.set(false);
		long now = System.nanoTime();
		long last = lastFrame;
		lastFrame = now;
		if (last == 0L) {
			return;
		}

		long gap = now - last;
		if (gap > longestFrame) {
			longestFrame = gap;
		}

		LocalPlayer player = mc.player;
		if (!panning || player == null) {
			return;
		}

		if (mc.screen == null && mc.getOverlay() == null) {
			panSeconds += Math.min(gap, 100_000_000L) / 1.0E9;
		}

		float yaw = baseYaw + (float) (panSeconds * PAN_DEGREES_PER_SECOND);
		player.setYRot(yaw);
		player.yRotO = yaw;
		player.setXRot(basePitch);
		player.xRotO = basePitch;
	}

	private static boolean hasServerPack(final Minecraft mc) {
		return mc.getResourceManager().listPacks().anyMatch(pack -> pack.packId().startsWith("server/"));
	}

	private static boolean idle(final Minecraft mc) {
		return mc.getOverlay() == null && BackgroundReload.current() == null;
	}

	private static PackSelectionModel model(final PackSelectionScreen screen) {
		try {
			Field field = PackSelectionScreen.class.getDeclaredField("model");
			field.setAccessible(true);
			return (PackSelectionModel) field.get(screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void waitForGo(final Minecraft mc) throws Exception {
		if (!SHOW) {
			origin = System.nanoTime();
			return;
		}

		Path go = mc.gameDirectory.toPath().resolve("rpo-go");
		log("waiting for {}", go);
		while (!Files.exists(go)) {
			Thread.sleep(20L);
		}

		Files.deleteIfExists(go);
		origin = System.nanoTime();
		event("go");
	}

	private static void waitForChunks(final Minecraft mc, final long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		int quietChecks = 0;
		while (quietChecks < 5 && System.currentTimeMillis() < deadline) {
			boolean done = CompletableFuture.supplyAsync(
				() -> mc.levelRenderer.countRenderedSections() > 0 && mc.levelRenderer.hasRenderedAllSections(), mc
			).join();
			quietChecks = done ? quietChecks + 1 : 0;
			Thread.sleep(done ? 2L : 5L);
		}
	}

	private static void event(final String name) {
		log("event {} at {} ms", name, (System.nanoTime() - origin) / 1_000_000L);
	}

	private static void onMain(final Minecraft mc, final Runnable task) {
		CompletableFuture.runAsync(task, mc).join();
	}

	private static void log(final String message, final Object... args) {
		ResourcePackOptimizer.LOGGER.info("[bench] " + message, args);
	}

	private static <T> T waitFor(final Supplier<T> supplier) throws InterruptedException {
		T value;
		while ((value = supplier.get()) == null) {
			Thread.sleep(10L);
		}

		return value;
	}

	private static void waitUntil(final Minecraft mc, final Supplier<Boolean> condition) throws InterruptedException {
		while (!CompletableFuture.supplyAsync(condition, mc).join()) {
			Thread.sleep(5L);
		}
	}

	private record Measure(long start, long doneMillis, long blockedMillis, long longestFrame) {
	}
}
