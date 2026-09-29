package dev.ravineclaw.rpo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.SpriteTicker;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

public final class ReuseGuard {
	private record Foreign(String mixin, @Nullable String method, boolean overwrite) {
	}

	private static final String OWN_MIXINS = "dev.ravineclaw.rpo.mixin.";
	private static final Pattern INJECTED_HANDLER = Pattern.compile("^[A-Za-z]+\\$[a-z]{3}[0-9a-f]{3}\\$");
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static final Map<String, List<Foreign>> FOREIGN = new ConcurrentHashMap<>();
	private static final List<Foreign> UNKNOWN = List.of(new Foreign("an unknown mixin", null, false));

	public static final String[] ATLASES = withTicker(names(
		SpriteLoader.class,
		TextureAtlas.class,
		SpriteContents.class,
		TextureAtlasSprite.class,
		Stitcher.class,
		AtlasSet.class,
		SpriteSourceList.class,
		SpriteSources.class,
		SpriteResourceLoader.class
	));
	public static final String[] MODELS = names(
		ModelManager.class,
		ModelBakery.class,
		ModelDiscovery.class,
		BlockStateModelLoader.class,
		ClientItemInfoLoader.class
	);
	public static final String[] FONTS = names(FontManager.class);
	public static final String[] TEXTURES = names(TextureManager.class, ReloadableTexture.class, SimpleTexture.class, TextureContents.class);
	public static final String[] SOUNDS = names(SoundManager.class, SoundEngine.class, SoundBufferLibrary.class);
	public static final String[] CHUNKS = names(LevelRenderer.class);

	private ReuseGuard() {
	}

	private static String[] withTicker(final String[] names) {
		String[] all = Arrays.copyOf(names, names.length + 1);
		all[names.length] = SpriteContents.class.getName();
		for (Class<?> nested : SpriteContents.class.getDeclaredClasses()) {
			if (SpriteTicker.class.isAssignableFrom(nested)) {
				all[names.length] = nested.getName();
			}
		}

		return all;
	}

	public static String[] names(final Class<?>... classes) {
		String[] names = new String[classes.length];
		for (int i = 0; i < classes.length; i++) {
			names[i] = classes[i].getName();
		}

		return names;
	}

	public static boolean untouched(final String what, final String... classNames) {
		try {
			for (String className : classNames) {
				for (Foreign foreign : FOREIGN.computeIfAbsent(className, ReuseGuard::findForeignMixins)) {
					if (foreign.method() != null && ModCompat.tolerates(foreign.mixin(), foreign.method(), foreign.overwrite())) {
						continue;
					}

					if (REPORTED.add(what + "|" + foreign.mixin())) {
						ResourcePackOptimizer.LOGGER.info(
							"Not reusing unchanged {} between reloads: {} modifies {}{}",
							what,
							foreign.mixin(),
							className,
							foreign.method() != null ? " (" + foreign.method() + ")" : ""
						);
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

	private static List<Foreign> findForeignMixins(final String className) {
		try {
			ClassLoader loader = ReuseGuard.class.getClassLoader();
			Class<?> target = Class.forName(className, false, loader);
			Set<String> vanilla = null;
			List<Foreign> found = new ArrayList<>();
			for (Method method : target.getDeclaredMethods()) {
				MixinMerged merged = method.getAnnotation(MixinMerged.class);
				if (merged == null || merged.mixin().startsWith(OWN_MIXINS)) {
					continue;
				}

				if (INJECTED_HANDLER.matcher(method.getName()).find()) {
					found.add(new Foreign(merged.mixin(), handlerName(method.getName()), false));
					continue;
				}

				if (vanilla == null) {
					vanilla = vanillaMethods(loader, className);
				}

				if (vanilla.contains(method.getName() + Type.getMethodDescriptor(method))) {
					found.add(new Foreign(merged.mixin(), method.getName(), true));
				}
			}

			return List.copyOf(found);
		} catch (ReflectiveOperationException | LinkageError | IOException | RuntimeException e) {
			return UNKNOWN;
		}
	}

	private static @Nullable String handlerName(final String merged) {
		String[] parts = merged.split("[$]", 4);
		if (parts.length < 4) {
			return null;
		}

		int bridge = parts[3].indexOf("$mixinextras$");
		String name = bridge < 0 ? parts[3] : parts[3].substring(0, bridge);
		return name.substring(name.lastIndexOf('$') + 1);
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
