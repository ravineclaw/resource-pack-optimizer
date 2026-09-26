package dev.ravineclaw.rpo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

public final class ReuseGuard {
	private static final String OWN_CONFIG = "resource_pack_optimizer.mixins.json";
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	public static final String[] ATLASES = {
		"net.minecraft.client.renderer.texture.SpriteLoader",
		"net.minecraft.client.renderer.texture.TextureAtlas",
		"net.minecraft.client.renderer.texture.SpriteContents",
		"net.minecraft.client.renderer.texture.Stitcher",
		"net.minecraft.client.resources.model.AtlasSet",
		"net.minecraft.client.renderer.texture.atlas.SpriteSourceList",
		"net.minecraft.client.renderer.texture.atlas.SpriteSources",
		"net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader"
	};
	public static final String[] MODELS = {
		"net.minecraft.client.resources.model.ModelManager",
		"net.minecraft.client.resources.model.ModelBakery",
		"net.minecraft.client.resources.model.BlockStateModelLoader"
	};
	public static final String[] FONTS = {
		"net.minecraft.client.gui.font.FontManager"
	};
	public static final String[] TEXTURES = {
		"net.minecraft.client.renderer.texture.TextureManager",
		"net.minecraft.client.renderer.texture.AbstractTexture",
		"net.minecraft.client.renderer.texture.SimpleTexture"
	};
	public static final String[] CHUNKS = {
		"net.minecraft.client.renderer.LevelRenderer"
	};
	public static final String[] SHADERS = {
		"net.minecraft.client.renderer.GameRenderer",
		"net.minecraft.client.renderer.ShaderInstance"
	};

	private ReuseGuard() {
	}

	public static boolean untouched(final String what, final String... classNames) {
		try {
			for (String className : classNames) {
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
