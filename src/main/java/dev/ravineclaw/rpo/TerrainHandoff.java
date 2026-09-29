package dev.ravineclaw.rpo;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

public final class TerrainHandoff {
	public static final String[] CLASSES = {
		"net.minecraft.client.renderer.LevelRenderer",
		"net.minecraft.client.renderer.ViewArea",
		"net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
		"net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
		"net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask",
		"net.minecraft.client.renderer.chunk.CompiledSectionMesh",
		"net.minecraft.client.renderer.chunk.ChunkSectionsToRender",
		"net.minecraft.client.renderer.extract.LevelExtractor"
	};
	private static final long TIMEOUT_NANOS = 3_000_000_000L;
	private static final long OFFER_NANOS = 10_000_000_000L;
	private static final Object LOCK = new Object();
	private static final Map<SectionRenderDispatcher.RenderSection, SectionMesh> HELD = new IdentityHashMap<>();

	private static volatile @Nullable SectionCompiler compiler;
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

	public interface Section {
		void rpo$apply(SectionMesh mesh);

		void rpo$release(SectionMesh mesh);

		void rpo$demote();
	}

	public interface Stamped {
		@Nullable SectionCompiler rpo$compiler();

		void rpo$setCompiler(@Nullable SectionCompiler compiler);
	}

	public interface Area {
		Iterable<SectionRenderDispatcher.RenderSection> rpo$sections();
	}

	public static boolean offer(final GpuTexture oldTexture, final GpuTextureView oldView) {
		if (!RpoSettings.active() || compiler != null || Minecraft.getInstance().level == null || !ReuseGuard.untouched("chunk meshes until rebuilt", CLASSES)) {
			return false;
		}

		closeOffer();
		offeredTexture = oldTexture;
		offeredView = oldView;
		offeredAt = System.nanoTime();
		return true;
	}

	public static void request(final boolean reload) {
		requested = reload && RpoSettings.active() && (offeredView != null || compiler != null);
	}

	public static boolean start(final SectionCompiler next, final SectionRenderDispatcher sections, final ViewArea viewArea) {
		boolean wanted = requested;
		requested = false;
		if (!wanted) {
			return false;
		}

		if (compiler != null) {
			if (dispatcher != sections || area != viewArea) {
				return false;
			}

			sections.lock();
			try {
				synchronized (LOCK) {
					releaseHeld();
					compiler = next;
				}
			} finally {
				sections.unlock();
			}

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
		compiler = next;
		return true;
	}

	public static GpuTextureView terrainAtlas(final GpuTextureView current) {
		GpuTextureView old = compiler != null ? view : offeredView;
		return old != null ? old : current;
	}

	public static @Nullable SectionMesh hold(final SectionRenderDispatcher.RenderSection section, final SectionMesh mesh) {
		if (compiler == null || !(mesh instanceof Stamped stamped)) {
			return null;
		}

		synchronized (LOCK) {
			SectionCompiler current = compiler;
			if (current == null || stamped.rpo$compiler() != current) {
				return null;
			}

			SectionMesh previous = HELD.put(section, mesh);
			return previous == null || previous == mesh ? CompiledSectionMesh.UNCOMPILED : previous;
		}
	}

	public static void dropHeld(final SectionRenderDispatcher.RenderSection section) {
		SectionRenderDispatcher sections = dispatcher;
		if (compiler == null || sections == null) {
			return;
		}

		sections.lock();
		try {
			synchronized (LOCK) {
				SectionMesh held = HELD.remove(section);
				if (held != null) {
					((Section)section).rpo$release(held);
				}
			}
		} finally {
			sections.unlock();
		}
	}

	public static void frame(final @Nullable ViewArea viewArea, final List<SectionRenderDispatcher.RenderSection> visible) {
		SectionCompiler current = compiler;
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
		if (offeredView != null && compiler == null && System.nanoTime() - offeredAt > OFFER_NANOS) {
			closeOffer();
		}
	}

	public static void abort() {
		requested = false;
		closeOffer();
		if (compiler == null) {
			return;
		}

		SectionRenderDispatcher sections = dispatcher;
		if (sections != null) {
			sections.lock();
		}

		try {
			synchronized (LOCK) {
				releaseHeld();
				compiler = null;
			}
		} finally {
			if (sections != null) {
				sections.unlock();
			}
		}

		lastResult = "#" + ++handoffs + ": aborted";
		finish();
	}

	public static boolean active() {
		return compiler != null;
	}

	public static boolean offered() {
		return offeredView != null;
	}

	public static @Nullable String lastResult() {
		return lastResult;
	}

	private static void commit(final SectionCompiler current, final long waited, final int waiting) {
		SectionRenderDispatcher sections = dispatcher;
		ViewArea viewArea = area;
		if (sections == null || viewArea == null) {
			abort();
			return;
		}

		int applied;
		int demoted = 0;
		sections.lock();
		try {
			synchronized (LOCK) {
				compiler = null;
				applied = HELD.size();
				for (Map.Entry<SectionRenderDispatcher.RenderSection, SectionMesh> entry : HELD.entrySet()) {
					((Section)entry.getKey()).rpo$apply(entry.getValue());
				}

				HELD.clear();
				for (SectionRenderDispatcher.RenderSection section : ((Area)viewArea).rpo$sections()) {
					if (section != null && isStale(section.getSectionMesh(), current)) {
						((Section)section).rpo$demote();
						demoted++;
					}
				}
			}
		} finally {
			sections.unlock();
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

	private static boolean isStale(final SectionMesh mesh, final SectionCompiler current) {
		return mesh instanceof Stamped stamped && stamped.rpo$compiler() != current;
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
