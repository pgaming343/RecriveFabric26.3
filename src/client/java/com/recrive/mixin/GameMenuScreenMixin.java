package com.recrive.mixin;

import com.recrive.ui.ProgressBarRenderer;
import com.recrive.ui.RecriveScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a small cloud-only (☁) square button to the pause screen (top-right). */
@Mixin(PauseScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
	protected GameMenuScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void recrive$addButton(CallbackInfo ci) {
		this.addRenderableWidget(Button.builder(Component.literal("☁"),
				b -> this.minecraft.setScreenAndShow(new RecriveScreen((PauseScreen) (Object) this)))
				.bounds(this.width - 28, 8, 20, 20).build());
	}

	/**
	 * Draws sync progress cards after everything else, so they render on top of
	 * the pause background blur (crisp) instead of underneath it (smeary).
	 */
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void recrive$drawProgress(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		ProgressBarRenderer.renderPauseCards(ctx, this.width);
	}
}