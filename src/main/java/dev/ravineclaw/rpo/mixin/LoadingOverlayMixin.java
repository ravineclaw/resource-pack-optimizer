package dev.ravineclaw.rpo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ravineclaw.rpo.ReloadChanges;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ReloadInstance reload;
	@Shadow
	@Final
	private boolean fadeIn;
	@Shadow
	private float currentProgress;
	@Shadow
	private long fadeOutStart;
	@Shadow
	private long fadeInStart;

	@ModifyConstant(method = "extractRenderState", constant = @Constant(floatValue = 1000.0F))
	private float rpo$noFadeOut(final float original) {
		return 1.0F;
	}

	@ModifyConstant(method = "extractRenderState", constant = @Constant(floatValue = 500.0F))
	private float rpo$noFadeIn(final float original) {
		return 1.0F;
	}

	@ModifyConstant(method = "isReadyToFadeOut", constant = @Constant(longValue = 1000L))
	private long rpo$noMinimumDuration(final long original) {
		return 0L;
	}

	@Shadow
	public abstract void tick();

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void rpo$finishPromptly(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a, final CallbackInfo ci) {
		if (this.fadeOutStart == -1L && this.fadeInStart != -1L && this.reload.isDone()) {
			this.tick();
		}
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
	private void rpo$markFinish(final Consumer<Object> onFinish, final Object result, final Operation<Void> original) {
		if (result instanceof Optional<?> optional && optional.isEmpty()) {
			ReloadChanges.finishReload(() -> original.call(onFinish, result));
		} else {
			original.call(onFinish, result);
		}
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
	private int rpo$blackBackground(final IntSupplier brandBackground) {
		return ARGB.color(255, 0, 0, 0);
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void rpo$inGameReload(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a, final CallbackInfo ci) {
		if (!this.fadeIn) {
			return;
		}

		ci.cancel();
		if (this.fadeInStart == -1L) {
			this.fadeInStart = Util.getMillis();
		}

		if (this.fadeOutStart > -1L) {
			this.minecraft.gui.setOverlay(null);
			if (this.minecraft.gui.screen() != null) {
				this.minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
			}

			return;
		}

		if (this.minecraft.gui.screen() != null) {
			this.minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
		} else {
			this.minecraft.gui.hud.extractDeferredSubtitles();
		}

		graphics.nextStratum();
		this.currentProgress = Mth.clamp(Math.max(this.currentProgress, this.currentProgress * 0.8F + this.reload.getActualProgress() * 0.2F), 0.0F, 1.0F);
		int width = graphics.guiWidth();
		graphics.fill(0, 0, width, 2, ARGB.color(96, 0, 0, 0));
		graphics.fill(0, 0, Mth.ceil(width * this.currentProgress), 2, ARGB.color(220, 255, 255, 255));
	}
}
