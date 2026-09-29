package dev.ravineclaw.rpo;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;

public final class FabricCompat {
	private interface Check {
		boolean allowed() throws ReflectiveOperationException;
	}

	private record Rule(Set<String> handlers, Check check) {
	}

	private static final String SPRITE_FINDER = "net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder";
	private static final Map<String, Rule> RULES = Map.of(
		"net.fabricmc.fabric.mixin.client.renderer.sprite.TextureAtlasMixin",
		new Rule(Set.of("uploadHook"), () -> spriteFinderField() != null),
		"net.fabricmc.fabric.mixin.client.rendering.AtlasManagerMixin",
		new Rule(Set.of("addAtlases"), () -> true),
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
			FabricCompat::noModelPlugins
		),
		"net.fabricmc.fabric.mixin.client.model.loading.ModelBakeryMixin",
		new Rule(Set.of("onReturnInit", "hookBlockModelBake", "withExtraModels", "wrapBlockModelBake", "wrapItemModelBake"), FabricCompat::noModelPlugins),
		"net.fabricmc.fabric.mixin.client.renderer.block.particle.LevelExtractorMixin",
		new Rule(Set.of("getParticleMaterialProxy", "captureViewBlockingPosition"), () -> true),
		"net.fabricmc.fabric.mixin.client.renderer.block.render.LevelExtractorMixin",
		new Rule(Set.of("hasMaterialFlagProxy"), () -> true),
		"net.fabricmc.fabric.mixin.client.rendering.LevelExtractorMixin",
		new Rule(Set.of("afterBlockOutlineExtraction", "afterExtractLevel", "onReload"), FabricCompat::noInvalidateListeners)
	);

	private static volatile @Nullable Field spriteFinder;
	private static volatile boolean spriteFinderSearched;

	private FabricCompat() {
	}

	public static boolean tolerates(final String mixin, final @Nullable String handler) {
		if (handler == null) {
			return false;
		}

		Rule rule = RULES.get(mixin);
		if (rule == null || !rule.handlers().contains(handler)) {
			return false;
		}

		try {
			return rule.check().allowed();
		} catch (Throwable t) {
			return false;
		}
	}

	public static void atlasSwapped(final TextureAtlas atlas) {
		Field field = spriteFinderField();
		if (field != null) {
			try {
				field.set(atlas, null);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't reset Fabric's sprite finder of {}", atlas.location(), e);
			}
		}
	}

	private static @Nullable Field spriteFinderField() {
		if (!spriteFinderSearched) {
			synchronized (FabricCompat.class) {
				if (!spriteFinderSearched) {
					spriteFinder = findSpriteFinderField();
					spriteFinderSearched = true;
				}
			}
		}

		return spriteFinder;
	}

	private static @Nullable Field findSpriteFinderField() {
		Field found = null;
		for (Field field : TextureAtlas.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals(SPRITE_FINDER)) {
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
		Class<?> plugins = Class.forName("net.fabricmc.fabric.impl.client.model.loading.ModelLoadingPluginManager", false, FabricCompat.class.getClassLoader());
		Class<?> deserializers = Class.forName("net.fabricmc.fabric.impl.client.model.loading.UnbakedModelDeserializerRegistry", false, FabricCompat.class.getClassLoader());
		return isEmpty(staticField(plugins, "PLUGINS")) && isEmpty(staticField(plugins, "PREPARABLE_PLUGINS")) && isEmpty(staticField(deserializers, "DESERIALIZERS"));
	}

	private static boolean noInvalidateListeners() throws ReflectiveOperationException {
		Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback", false, FabricCompat.class.getClassLoader());
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
