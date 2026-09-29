package dev.ravineclaw.rpo;

import dev.ravineclaw.rpo.mixin.SectionRenderDispatcherAccessor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

public final class TerrainHandoff {
	public static final String[] CLASSES = classes();
	private static final long TIMEOUT_NANOS = 3_000_000_000L;
	private static final long OFFER_NANOS = 10_000_000_000L;
	private static final Object LOCK = new Object();
	private static final Map<SectionRenderDispatcher.RenderSection, SectionMesh> HELD = new IdentityHashMap<>();

	private static volatile @Nullable Object epoch;
	private static @Nullable SectionRenderDispatcher dispatcher;
	private static @Nullable ViewArea area;
	private static @Nullable GpuTexture texture;
	private static @Nullable GpuTextureView view;
	private static long startedAt;
	private static int blank;
	private static @Nullable GpuTexture offeredTexture;
	private static @Nullable GpuTextureView offeredView;
	private static long offeredAt;
	private static boolean requested;
	private static volatile @Nullable String lastResult;
	private static int handoffs;

	private TerrainHandoff() {
	}

	private static String[] classes() {
		List<Class<?>> list = new ArrayList<>(List.of(
			LevelRenderer.class,
			ViewArea.class,
			SectionRenderDispatcher.class,
			SectionRenderDispatcher.RenderSection.class,
			SectionRenderDispatcher.RenderSection.CompileTask.class,
			CompiledSectionMesh.class,
			ChunkSectionsToRender.class
		));
		list.addAll(List.of(SectionRenderDispatcher.RenderSection.class.getDeclaredClasses()));
		return list.stream().map(Class::getName).toArray(String[]::new);
	}

	public interface Section {
		void rpo$apply(SectionMesh mesh);

		void rpo$release(SectionMesh mesh);

		void rpo$demote();

		void rpo$invalidate();
	}

	public interface Stamped {
		@Nullable Object rpo$epoch();

		void rpo$setEpoch(@Nullable Object epoch);
	}

	public static boolean offer(final GpuTexture oldTexture, final GpuTextureView oldView) {
		if (!RpoSettings.active() || epoch != null || Minecraft.getInstance().level == null || !ReuseGuard.untouched("chunk meshes until rebuilt", CLASSES)) {
			return false;
		}

		closeOffer();
		offeredTexture = oldTexture;
		offeredView = oldView;
		offeredAt = System.nanoTime();
		return true;
	}

	public static void request(final boolean reload) {
		requested = reload && RpoSettings.active() && (offeredView != null || epoch != null);
	}

	public static boolean start(final SectionRenderDispatcher sections, final ViewArea viewArea) {
		boolean wanted = requested;
		requested = false;
		if (!wanted) {
			return false;
		}

		Object next = new Object();

		if (epoch != null) {
			if (dispatcher != sections || area != viewArea) {
				return false;
			}

			synchronized (LOCK) {
				releaseHeld();
				epoch = next;
			}
			invalidate(viewArea);

			startedAt = System.nanoTime();
			blank = 0;
			return true;
		}

		if (offeredView == null || offeredTexture == null) {
			return false;
		}

		texture = offeredTexture;
		view = offeredView;
		offeredTexture = null;
		offeredView = null;
		dispatcher = sections;
		area = viewArea;
		startedAt = System.nanoTime();
		blank = 0;
		epoch = next;
		invalidate(viewArea);
		return true;
	}

	private static void invalidate(final ViewArea viewArea) {
		for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
			if (section != null) {
				((Section)section).rpo$invalidate();
			}
		}
	}

	public static void release(final SectionMesh mesh) {
		SectionRenderDispatcher sections = dispatcher;
		if (mesh != CompiledSectionMesh.UNCOMPILED) {
			if (sections != null) {
				((SectionRenderDispatcherAccessor)sections).rpo$toClose().add(mesh);
			} else {
				mesh.close();
			}
		}
	}

	public static @Nullable Object epoch() {
		return epoch;
	}

	public static GpuTextureView terrainAtlas(final GpuTextureView current) {
		GpuTextureView old = epoch != null ? view : offeredView;
		return old != null ? old : current;
	}

	public static @Nullable SectionMesh hold(final SectionRenderDispatcher.RenderSection section, final SectionMesh mesh) {
		if (epoch == null || !(mesh instanceof Stamped stamped)) {
			return null;
		}

		synchronized (LOCK) {
			Object current = epoch;
			if (current == null || stamped.rpo$epoch() != current) {
				return null;
			}

			SectionMesh previous = HELD.put(section, mesh);
			return previous == null || previous == mesh ? CompiledSectionMesh.UNCOMPILED : previous;
		}
	}

	public static void dropHeld(final SectionRenderDispatcher.RenderSection section) {
		SectionRenderDispatcher sections = dispatcher;
		if (epoch == null || sections == null) {
			return;
		}

		synchronized (LOCK) {
			SectionMesh held = HELD.remove(section);
			if (held != null) {
				((Section)section).rpo$release(held);
			}
		}
	}

	public static void frame(final @Nullable ViewArea viewArea, final List<SectionRenderDispatcher.RenderSection> visible) {
		Object current = epoch;
		if (current == null) {
			return;
		}

		if (viewArea == null || viewArea != area) {
			abort();
			return;
		}

		long waited = System.nanoTime() - startedAt;
		boolean timedOut = waited > TIMEOUT_NANOS;
		int waiting = 0;
		int empty = 0;
		synchronized (LOCK) {
			for (SectionRenderDispatcher.RenderSection section : visible) {
				SectionMesh mesh = section.getSectionMesh();
				if (mesh == CompiledSectionMesh.UNCOMPILED) {
					empty++;
				} else if (isStale(mesh, current) && !HELD.containsKey(section)) {
					waiting++;
				}
			}
		}

		blank = Math.max(blank, empty);

		if (waiting == 0 || timedOut) {
			commit(current, waited, waiting);
		}
	}

	public static void tick() {
		if (offeredView != null && epoch == null && System.nanoTime() - offeredAt > OFFER_NANOS) {
			closeOffer();
		}
	}

	public static void abort() {
		requested = false;
		closeOffer();
		if (epoch == null) {
			return;
		}

		synchronized (LOCK) {
			releaseHeld();
			epoch = null;
		}

		lastResult = "#" + ++handoffs + ": aborted";
		finish();
	}

	public static boolean active() {
		return epoch != null;
	}

	public static boolean offered() {
		return offeredView != null;
	}

	public static @Nullable String lastResult() {
		return lastResult;
	}

	private static void commit(final Object current, final long waited, final int waiting) {
		SectionRenderDispatcher sections = dispatcher;
		ViewArea viewArea = area;
		if (sections == null || viewArea == null) {
			abort();
			return;
		}

		int applied;
		int demoted = 0;
		synchronized (LOCK) {
			epoch = null;
			applied = HELD.size();
			for (Map.Entry<SectionRenderDispatcher.RenderSection, SectionMesh> entry : HELD.entrySet()) {
				((Section)entry.getKey()).rpo$apply(entry.getValue());
			}

			HELD.clear();
			for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
				if (section != null && isStale(section.getSectionMesh(), current)) {
					((Section)section).rpo$demote();
					demoted++;
				}
			}
		}

		lastResult = String.format(
			"#%d: %d sections switched after %d ms%s, %d not rebuilt yet, at most %d visible sections without a mesh",
			++handoffs,
			applied,
			waited / 1_000_000L,
			waiting > 0 ? " (timed out, " + waiting + " visible left)" : "",
			demoted,
			blank
		);
		ResourcePackOptimizer.LOGGER.debug("Chunk mesh handoff: {}", lastResult);
		finish();
	}

	private static boolean isStale(final SectionMesh mesh, final Object current) {
		return mesh instanceof Stamped stamped && stamped.rpo$epoch() != current;
	}

	private static void releaseHeld() {
		for (Map.Entry<SectionRenderDispatcher.RenderSection, SectionMesh> entry : HELD.entrySet()) {
			((Section)entry.getKey()).rpo$release(entry.getValue());
		}

		HELD.clear();
	}

	private static void finish() {
		List<AutoCloseable> old = new ArrayList<>(2);
		if (view != null) {
			old.add(view);
		}

		if (texture != null) {
			old.add(texture);
		}

		view = null;
		texture = null;
		dispatcher = null;
		area = null;
		FramePump.closeLater(old);
	}

	private static void closeOffer() {
		List<AutoCloseable> old = new ArrayList<>(2);
		if (offeredView != null) {
			old.add(offeredView);
		}

		if (offeredTexture != null) {
			old.add(offeredTexture);
		}

		offeredView = null;
		offeredTexture = null;
		FramePump.closeLater(old);
	}
}
