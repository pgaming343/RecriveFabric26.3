package com.recrive.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Punchy-style flat widget: solid colour + bevel instead of the vanilla sprite. */
public class FlatButton extends AbstractWidget {
	private final Font font;
	private final Runnable action;
	private final int fill;
	private final int hover;
	private final int edgeTop;
	private final int edgeBottom;
	private final int textColour;

	private FlatButton(Font font, int x, int y, int w, int h, Component msg, Runnable action,
			int fill, int hover, int edgeTop, int edgeBottom, int textColour) {
		super(x, y, w, h, msg);
		this.font = font;
		this.action = action;
		this.fill = fill;
		this.hover = hover;
		this.edgeTop = edgeTop;
		this.edgeBottom = edgeBottom;
		this.textColour = textColour;
	}

	/** Light punchy-gray button (white label), for normal/secondary actions. */
	public static FlatButton flat(Font font, int x, int y, int w, int h, Component msg, Runnable action) {
		return new FlatButton(font, x, y, w, h, msg, action,
				0xFFC6C6C6, 0xFFDBDBDB, 0xFFFFFFFF, 0xFF8B8B8B, 0xFFFFFFFF);
	}

	/** Green punchy button (white label), for primary actions / selection. */
	public static FlatButton green(Font font, int x, int y, int w, int h, Component msg, Runnable action) {
		return new FlatButton(font, x, y, w, h, msg, action,
				0xFF359B26, 0xFF41B930, 0xFF6BD458, 0xFF1F6E14, 0xFFFFFFFF);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (this.isActive()) {
			this.playDownSound(Minecraft.getInstance().getSoundManager());
			this.action.run();
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int f = this.isActive() ? (this.isHoveredOrFocused() ? this.hover : this.fill) : 0xFF8F8F8F;
		if (this.isActive() && this.isHoveredOrFocused()) {
			g.fill(this.getX() - 1, this.getY() - 1, this.getRight() + 1, this.getBottom() + 1, 0xFFFFFFFF);
		}
		g.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0xFF000000);
		g.fill(this.getX() + 2, this.getY() + 2, this.getRight() - 2, this.getBottom() - 2, f);
		if (this.isActive()) {
			g.fill(this.getX() + 2, this.getY() + 2, this.getRight() - 2, this.getY() + 4, this.edgeTop);
			g.fill(this.getX() + 2, this.getBottom() - 4, this.getRight() - 2, this.getBottom() - 2, this.edgeBottom);
		}
		String label = this.font.plainSubstrByWidth(this.getMessage().getString(), this.width - 8);
		g.centeredText(this.font, label, this.getX() + this.width / 2,
				this.getY() + (this.height - 8) / 2, this.isActive() ? this.textColour : 0xFF5E5E5E);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		this.defaultButtonNarrationText(out);
	}
}
