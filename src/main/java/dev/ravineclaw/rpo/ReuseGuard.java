package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.SpriteTicker;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.ModelManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

public final class ReuseGuard {
	private static final String OWN_MIXINS = "dev.ravineclaw.rpo.mixin.";
	private static final Pattern INJECTED_HANDLER = Pattern.compile("^[A-Za-z]+\\$[a-z]{3}[0-9a-f]{3}\\$");
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static final Map<String, String> FOREIGN = new ConcurrentHashMap<>();

	public static final Class<?>[] ATLASES = withTicker(new Class<?>[] {
		SpriteLoader.class,
		TextureAtlas.class,
		SpriteContents.class,
		TextureAtlasSprite.class,
		Stitcher.class,
		AtlasSet.class,
		SpriteSourceList.class,
		SpriteSources.class,
		SpriteResourceLoader.class
	});
	public static final Class<?>[] MODELS = {
		ModelManager.class,
		ModelBakery.class,
		ModelDiscovery.class,
		BlockStateModelLoader.class
	};
	public static final Class<?>[] FONTS = {
		FontManager.class
	};
	public static final Class<?>[] TEXTURES = {
		TextureManager.class,
		AbstractTexture.class,
		SimpleTexture.class
	};
	public static final Class<?>[] CHUNKS = {
		LevelRenderer.class
	};

	private ReuseGuard() {
	}

	private static Class<?>[] withTicker(final Class<?>[] base) {
		Class<?>[] all = Arrays.copyOf(base, base.length + 1);
		all[base.length] = SpriteContents.class;
		for (Class<?> nested : SpriteContents.class.getDeclaredClasses()) {
			if (SpriteTicker.class.isAssignableFrom(nested)) {
				all[base.length] = nested;
			}
		}

		return all;
	}

	public static boolean untouched(final String what, final Class<?>... classes) {
		try {
			for (Class<?> type : classes) {
				String className = type.getName();
				String mixin = FOREIGN.computeIfAbsent(className, ReuseGuard::findForeignMixin);
				if (!mixin.isEmpty()) {
					if (REPORTED.add(what + "|" + mixin)) {
						ResourcePackOptimizer.LOGGER.info("Not reusing unchanged {} between reloads: {} modifies {}", what, mixin, className);
					}

					return false;
				}
			}

			return true;
		} catch (Throwable t) {
			if (REPORTED.add(what + "|error")) {
				ResourcePackOptimizer.LOGGER.warn("Couldn't check mixins for {}, reloading it normally", what, t);
			}

			return false;
		}
	}

	private static String findForeignMixin(final String className) {
		try {
			ClassLoader loader = ReuseGuard.class.getClassLoader();
			Class<?> target = Class.forName(className, false, loader);
			Set<String> vanilla = null;
			for (Method method : target.getDeclaredMethods()) {
				MixinMerged merged = method.getAnnotation(MixinMerged.class);
				if (merged == null || merged.mixin().startsWith(OWN_MIXINS)) {
					continue;
				}

				if (INJECTED_HANDLER.matcher(method.getName()).find()) {
					return merged.mixin();
				}

				if (vanilla == null) {
					vanilla = vanillaMethods(loader, className);
				}

				if (vanilla.contains(method.getName() + Type.getMethodDescriptor(method))) {
					return merged.mixin();
				}
			}

			return "";
		} catch (ReflectiveOperationException | LinkageError | IOException | RuntimeException e) {
			return "an unknown mixin";
		}
	}

	private static Set<String> vanillaMethods(final ClassLoader loader, final String className) throws IOException {
		try (InputStream stream = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
			if (stream == null) {
				throw new IOException("No original class for " + className);
			}

			ClassNode node = new ClassNode();
			new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			Set<String> methods = new HashSet<>();
			for (MethodNode method : node.methods) {
				methods.add(method.name + method.desc);
			}

			return methods;
		}
	}
}
