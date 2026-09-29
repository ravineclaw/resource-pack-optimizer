package dev.ravineclaw.rpo;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

public final class ModCompat {
	private interface Check {
		boolean allowed() throws ReflectiveOperationException;
	}

	private record Rule(Set<String> handlers, Set<String> overwrites, Check check) {
		private Rule(final Set<String> handlers, final Check check) {
			this(handlers, Set.of(), check);
		}
	}

	private static final String FABRIC_SPRITE_FINDER = "net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder";
	private static final String IRIS_TEXTURE_TRACKER = "net.irisshaders.iris.pbr.TextureTracker";
	private static final String SODIUM_SPRITE_FINDER_CACHE = "net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache";
	private static final Map<String, Rule> RULES = Map.ofEntries(
		Map.entry("net.fabricmc.fabric.mixin.client.renderer.sprite.TextureAtlasMixin", new Rule(Set.of("uploadHook"), () -> fabricSpriteFinder() != null)),
		Map.entry("net.fabricmc.fabric.mixin.client.rendering.AtlasManagerMixin", new Rule(Set.of("addAtlases"), () -> true)),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.model.loading.ModelManagerMixin",
			new Rule(
				Set.of(
					"onHeadReload",
					"resetEventDispatcherFuture",
					"hookModels",
					"hookBlockStateModels",
					"hookModelCollect",
					"hookModelBaking",
					"resolveExtraModels",
					"onReturnUpload",
					"cancelVanillaDeserialize",
					"actuallyDeserializeModel"
				),
				ModCompat::noModelPlugins
			)
		),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.model.loading.ModelBakeryMixin",
			new Rule(Set.of("onReturnInit", "hookBlockModelBake", "withExtraModels", "wrapBlockModelBake", "wrapItemModelBake"), ModCompat::noModelPlugins)
		),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.renderer.block.particle.LevelExtractorMixin",
			new Rule(Set.of("getParticleMaterialProxy", "captureViewBlockingPosition"), () -> true)
		),
		Map.entry("net.fabricmc.fabric.mixin.client.renderer.block.render.LevelExtractorMixin", new Rule(Set.of("hasMaterialFlagProxy"), () -> true)),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.rendering.LevelExtractorMixin",
			new Rule(Set.of("afterBlockOutlineExtraction", "afterExtractLevel", "onReload"), ModCompat::noInvalidateListeners)
		),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.renderer.block.render.LevelRendererMixin",
			new Rule(Set.of("beforeCreateRandom", "cancelCollectParts", "submitBreakingBlockModelProxy"), () -> true)
		),
		Map.entry(
			"net.fabricmc.fabric.mixin.client.rendering.LevelRendererMixin",
			new Rule(
				Set.of(
					"beforeRender",
					"prepareChunkRenders",
					"wrapRenderOpaqueTerrain",
					"onCreatePoseStack",
					"afterCollectSubmits",
					"afterRenderSolidFeatures",
					"afterRenderClassicTranslucentFeatures",
					"afterRenderOitTranslucentFeatures",
					"beforeRenderBlockOutline",
					"beforeCollectGizmos",
					"wrapRenderTranslucentTerrain",
					"endMainRender"
				),
				() -> true
			)
		),
		Map.entry("net.fabricmc.fabric.mixin.client.sound.SoundEngineMixin", new Rule(Set.of("getStream"), () -> true)),
		Map.entry("net.caffeinemc.mods.sodium.mixin.core.render.TextureAtlasMixin", new Rule(Set.of("deleteSpriteFinder"), () -> sodiumSpriteFinder() != null)),
		Map.entry("net.caffeinemc.mods.sodium.mixin.features.textures.animations.tracking.TextureAtlasMixin", new Rule(Set.of("preReturnSprite"), () -> true)),
		Map.entry("net.caffeinemc.mods.sodium.mixin.features.textures.animations.tracking.AtlasManagerMixin", new Rule(Set.of("catchUsedSprites"), () -> true)),
		Map.entry(
			"net.caffeinemc.mods.sodium.mixin.features.textures.animations.tracking.SpriteContentsTickerMixin",
			new Rule(Set.of("assignParent", "captureActiveState", "preTick"), () -> true)
		),
		Map.entry("net.caffeinemc.mods.sodium.mixin.features.textures.scan.TextureAtlasSpriteMixin", new Rule(Set.of("hookTickerInstantiation"), () -> true)),
		Map.entry(
			"net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin",
			new Rule(
				Set.of("setRenderer", "extractVisibleBlockEntities", "replaceBlockEntityIteration", "cullTerrain", "cancel"),
				Set.of("sectionStatistics", "setBlocksDirty", "setSectionDirtyWithNeighbors", "setBlockDirty", "setSectionDirty", "countRenderedSections"),
				() -> true
			)
		),
		Map.entry("net.irisshaders.iris.mixin.texture.MixinSpriteContents", new Rule(Set.of("redirectMipmapGeneration"), () -> true)),
		Map.entry("net.irisshaders.iris.mixin.texture.pbr.MixinSpriteContents", new Rule(Set.of("onTailClose", "onTailMarkActive"), () -> true)),
		Map.entry("net.irisshaders.iris.mixin.texture.MixinTextureManager", new Rule(Set.of("onTailReloadLambda", "onInnerDumpTextures", "onTailClose"), () -> true)),
		Map.entry("net.irisshaders.iris.mixin.texture.pbr.MixinReloadableTexture", new Rule(Set.of("onDoLoad"), () -> true)),
		Map.entry("net.irisshaders.iris.mixin.texture.pbr.MixinTextureAtlas", new Rule(Set.of("onTailCycleAnimationFrames", "onUpload"), () -> irisTracker() != null)),
		Map.entry(
			"net.irisshaders.iris.mixin.fabulous.MixinDisableFabulousGraphics",
			new Rule(Set.of("disableFabulousGraphicsOnResourceReload", "disableFabulousGraphicsOnLevelRendererReload"), () -> true)
		),
		Map.entry(
			"me.flashyreese.mods.sodiumextra.mixin.animation.MixinTextureAtlas",
			new Rule(Set.of("cycleAnimationFrames", "upload"), () -> sodiumExtraSetSprite() != null)
		),
		Map.entry("net.irisshaders.iris.mixin.MixinLevelRenderer_SkipRendering", new Rule(Set.of("skipRenderEntities"), () -> true))
	);

	private static volatile @Nullable Field fabricSpriteFinder;
	private static volatile boolean fabricSearched;
	private static volatile @Nullable SodiumHooks sodiumSpriteFinder;
	private static volatile boolean sodiumSearched;

	private static volatile @Nullable Method sodiumExtraSetSprite;
	private static volatile boolean sodiumExtraSearched;
	private static volatile @Nullable IrisHooks irisTracker;
	private static volatile boolean irisSearched;

	private record SodiumHooks(Method resetBlocks, Method resetItems, Field isBlocks) {
	}

	private record IrisHooks(Object tracker, Method track) {
	}

	private ModCompat() {
	}

	public static boolean tolerates(final String mixin, final String method, final boolean overwrite) {
		Rule rule = RULES.get(mixin);
		if (rule == null || !(overwrite ? rule.overwrites() : rule.handlers()).contains(method)) {
			return false;
		}

		try {
			return rule.check().allowed();
		} catch (Throwable t) {
			return false;
		}
	}

	@SuppressWarnings("deprecation")
	public static void atlasSwapped(final TextureAtlas atlas) {
		Field fabric = fabricSpriteFinder();
		if (fabric != null) {
			try {
				fabric.set(atlas, null);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't reset Fabric's sprite finder of {}", atlas.location(), e);
			}
		}

		SodiumHooks sodium = sodiumSpriteFinder();
		if (sodium != null) {
			try {
				if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
					sodium.resetBlocks().invoke(null);
					sodium.isBlocks().setBoolean(atlas, true);
				} else if (atlas.location().equals(TextureAtlas.LOCATION_ITEMS)) {
					sodium.resetItems().invoke(null);
					sodium.isBlocks().setBoolean(atlas, false);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't reset Sodium's sprite finder of {}", atlas.location(), e);
			}
		}

		IrisHooks iris = irisTracker();
		if (iris != null) {
			try {
				Object texture = atlas.getTexture();
				iris.track().invoke(iris.tracker(), texture.getClass().getMethod("iris$getGlId").invoke(texture), atlas);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't register {} with Iris", atlas.location(), e);
			}
		}
	}

	public static void animationStateCreated(final SpriteContents.AnimationState state, final TextureAtlasSprite sprite) {
		Method setSprite = sodiumExtraSetSprite();
		if (setSprite != null) {
			try {
				setSprite.invoke(state, sprite);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't hand {} to Sodium Extra", sprite.contents().name(), e);
			}
		}
	}

	private static @Nullable Method sodiumExtraSetSprite() {
		if (!sodiumExtraSearched) {
			synchronized (ModCompat.class) {
				if (!sodiumExtraSearched) {
					try {
						sodiumExtraSetSprite = SpriteContents.AnimationState.class.getMethod("sodium_extra$setSprite", TextureAtlasSprite.class);
					} catch (NoSuchMethodException | RuntimeException e) {
						sodiumExtraSetSprite = null;
					}

					sodiumExtraSearched = true;
				}
			}
		}

		return sodiumExtraSetSprite;
	}

	private static @Nullable IrisHooks irisTracker() {
		if (!irisSearched) {
			synchronized (ModCompat.class) {
				if (!irisSearched) {
					irisTracker = findIrisHooks();
					irisSearched = true;
				}
			}
		}

		return irisTracker;
	}

	private static @Nullable IrisHooks findIrisHooks() {
		try {
			Class<?> tracker = Class.forName(IRIS_TEXTURE_TRACKER, false, ModCompat.class.getClassLoader());
			Object instance = tracker.getField("INSTANCE").get(null);
			Method track = tracker.getMethod("trackTexture", int.class, AbstractTexture.class);
			return instance != null ? new IrisHooks(instance, track) : null;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Field fabricSpriteFinder() {
		if (!fabricSearched) {
			synchronized (ModCompat.class) {
				if (!fabricSearched) {
					fabricSpriteFinder = onlyField(FABRIC_SPRITE_FINDER, null);
					fabricSearched = true;
				}
			}
		}

		return fabricSpriteFinder;
	}

	private static @Nullable SodiumHooks sodiumSpriteFinder() {
		if (!sodiumSearched) {
			synchronized (ModCompat.class) {
				if (!sodiumSearched) {
					sodiumSpriteFinder = findSodiumHooks();
					sodiumSearched = true;
				}
			}
		}

		return sodiumSpriteFinder;
	}

	private static @Nullable SodiumHooks findSodiumHooks() {
		try {
			Class<?> cache = Class.forName(SODIUM_SPRITE_FINDER_CACHE, false, ModCompat.class.getClassLoader());
			Method resetBlocks = cache.getMethod("resetSpriteFinder");
			Method resetItems = cache.getMethod("resetItemSpriteFinder");
			Field isBlocks = onlyField("boolean", "isBlocks");
			if (!Modifier.isStatic(resetBlocks.getModifiers()) || !Modifier.isStatic(resetItems.getModifiers()) || isBlocks == null) {
				return null;
			}

			return new SodiumHooks(resetBlocks, resetItems, isBlocks);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable Field onlyField(final String type, final @Nullable String name) {
		Field found = null;
		for (Field field : TextureAtlas.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals(type) && (name == null || field.getName().equals(name))) {
				if (found != null) {
					return null;
				}

				found = field;
			}
		}

		if (found != null) {
			try {
				found.setAccessible(true);
			} catch (RuntimeException e) {
				return null;
			}
		}

		return found;
	}

	private static boolean noModelPlugins() throws ReflectiveOperationException {
		Class<?> plugins = Class.forName("net.fabricmc.fabric.impl.client.model.loading.ModelLoadingPluginManager", false, ModCompat.class.getClassLoader());
		Class<?> deserializers = Class.forName("net.fabricmc.fabric.impl.client.model.loading.UnbakedModelDeserializerRegistry", false, ModCompat.class.getClassLoader());
		return isEmpty(staticField(plugins, "PLUGINS")) && isEmpty(staticField(plugins, "PREPARABLE_PLUGINS")) && isEmpty(staticField(deserializers, "DESERIALIZERS"));
	}

	private static boolean noInvalidateListeners() throws ReflectiveOperationException {
		Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback", false, ModCompat.class.getClassLoader());
		Object event = staticField(callback, "EVENT");
		Field handlers = event.getClass().getDeclaredField("handlers");
		handlers.setAccessible(true);
		Object array = handlers.get(event);
		return array != null && array.getClass().isArray() && Array.getLength(array) == 0;
	}

	private static Object staticField(final Class<?> owner, final String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static boolean isEmpty(final Object value) {
		if (value instanceof Collection<?> collection) {
			return collection.isEmpty();
		}

		if (value instanceof Map<?, ?> map) {
			return map.isEmpty();
		}

		return false;
	}
}
