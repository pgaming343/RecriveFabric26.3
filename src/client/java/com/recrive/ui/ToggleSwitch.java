package com.recrive.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Punchy-style on/off toggle: green track + white knob when on, gray knob-left when off. */
public class ToggleSwitch extends AbstractWidget {
	private final Runnable action;
	private final boolean on;

	public ToggleSwitch(int x, int y, int w, int h, boolean on, Runnable action) {
		super(x, y, w, h, Component.literal(on ? "ON" : "OFF"));
		this.on = on;
		this.action = action;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		this.playDownSound(Minecraft.getInstance().getSoundManager());
		this.action.run();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int x = this.getX(), y = this.getY(), w = this.width, h = this.height;

		if (this.isHoveredOrFocused()) {
			g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFFFFFFFF);
		}
		g.fill(x, y, x + w, y + h, 0xFF000000);
		if (this.on) {
			int half = 1 + (w - 2) / 2;
			g.fill(x + 1, y + 1, x + half, y + h - 1, 0xFF2FA11F);
			g.fill(x + 1, y + 1, x + half, y + 3, 0xFF43C22F);
			g.fill(x + half, y + 1, x + w - 1, y + h - 1, 0xFF3A3E44);
			g.fill(x + half, y + 1, x + w - 1, y + 3, 0xFF4A4F55);
		} else {
			g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF4A4F55);
			g.fill(x + 1, y + 1, x + w - 1, y + 3, 0xFF565C63);
		}

		int kw = h - 8;
		int kx = this.on ? x + w - kw - 4 : x + 3;
		int ky = y + 4;
		g.fill(kx - 1, ky - 1, kx + kw + 1, ky + kw + 1, 0xFF000000);
		g.fill(kx, ky, kx + kw, ky + kw, this.on ? 0xFFE8E8E8 : 0xFFB0B0B0);
		g.fill(kx, ky, kx + kw, ky + 2, 0xFFFFFFFF);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		this.defaultButtonNarrationText(out);
	}
}
