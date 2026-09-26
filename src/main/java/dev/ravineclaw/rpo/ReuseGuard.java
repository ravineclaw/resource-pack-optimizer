package dev.ravineclaw.rpo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.client.resources.model.AtlasSet;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

public final class ReuseGuard {
	private static final String OWN_CONFIG = "resource_pack_optimizer.mixins.json";
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	public static final Class<?>[] ATLASES = {
		SpriteLoader.class,
		TextureAtlas.class,
		SpriteContents.class,
		Stitcher.class,
		AtlasSet.class,
		SpriteSourceList.class,
		SpriteSources.class,
		SpriteResourceLoader.class
	};
	public static final Class<?>[] MODELS = {
		ModelManager.class,
		ModelBakery.class,
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
	public static final Class<?>[] SHADERS = {
		GameRenderer.class,
		ShaderInstance.class
	};

	private ReuseGuard() {
	}

	public static boolean untouched(final String what, final Class<?>... classes) {
		try {
			for (Class<?> type : classes) {
				String className = type.getName();
				ClassInfo info = ClassInfo.forName(className.replace('.', '/'));
				if (info == null) {
					return false;
				}

				for (IMixinInfo mixin : info.getAppliedMixins()) {
					if (!OWN_CONFIG.equals(mixin.getConfig().getName())) {
						if (REPORTED.add(what + "|" + mixin.getClassName())) {
							ResourcePackOptimizer.LOGGER.info(
								"Not reusing unchanged {} between reloads: {} (from {}) modifies {}",
								what, mixin.getClassName(), mixin.getConfig().getName(), className
							);
						}

						return false;
					}
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
}
