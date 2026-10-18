package com.recrive.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RecriveInfoScreen extends Screen {
	private final Screen parent;
	private int scrollY = 0;
	private int contentH = 0;

	public RecriveInfoScreen(Screen parent) {
		super(Component.literal("Info & Safety"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
				.bounds(this.width / 2 - 50, this.height - 30, 100, 20).build());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int visible = this.height - 60;
		scrollY -= (int) (sy * 14);
		if (scrollY < 0) scrollY = 0;
		int max = Math.max(0, contentH - visible);
		if (scrollY > max) scrollY = max;
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0xF0101216);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		super.extractRenderState(ctx, mouseX, mouseY, delta);

		int panelW = Math.min(this.width - 40, 520);
		int x = (this.width - panelW) / 2 + 14;
		int textW = panelW - 28;
		int top = 16;
		int visible = this.height - 60;

		String[][] lines = {
			{"h", "\u2601 Recrive \u2014 Cloud Backup"},
			{"", ""},
			{"s", "WHAT IT DOES"},
			{"t", "Saves a copy of each world to your Google Drive so you never lose progress."},
			{"", ""},
			{"s", "HOW TO SET UP"},
			{"t", "1. Click Connect on the Account tab to link your Google Drive."},
			{"t", "2. Flip the AutoSync switch ON next to a world to back it up."},
			{"t", "3. Done! Backups happen automatically in the background."},
			{"", ""},
			{"s", "AUTO-SYNC vs SYNC NOW"},
			{"t", "AutoSync: uploads your world quietly every few minutes in the background. Change the interval in Settings (5/10/30/60 min)."},
			{"t", "Sync Now: click the Sync Now button for a one-time backup right away. Use this before a big build or when you want an immediate save to Drive."},
			{"", ""},
			{"s", "ZIP BACKUPS KEPT ON DRIVE"},
			{"t", "Each sync replaces the old .zip on Drive with a new one. This setting controls how many old versions to keep before deleting the oldest. 1 means keep only the latest backup. 3 means keep the last 3 versions."},
			{"", ""},
			{"r", "PRECAUTIONS"},
			{"t", "1. Close the world first for a perfect backup."},
			{"t", "2. While playing, saves are flushed first but the backup may miss the last few seconds."},
			{"t", "3. Restore replaces your current world with the Drive copy. A local .bak folder is saved first."},
			{"t", "4. Make sure you have enough space on Google Drive."},
			{"t", "5. Keep rclone installed (needed to talk to Google Drive)."},
			{"t", "6. Don't delete .zip backups from Drive unless you have a local copy."}
		};

		int y = top - scrollY;
		contentH = 0;

		for (String[] line : lines) {
			String type = line[0];
			String text = line[1];
			if (type.isEmpty()) { y += 6; contentH += 6; continue; }

			int color = switch (type) {
				case "h" -> 0xFF9BD1FF;
				case "s" -> 0xFFFFE082;
				case "r" -> 0xFFFF5555;
				default -> 0xFFE8E8E8;
			};

			StringBuilder cur = new StringBuilder();
			for (String word : text.split(" ")) {
				String test = cur.length() == 0 ? word : cur + " " + word;
				if (this.font.width(test) <= textW) {
					cur.setLength(0);
					cur.append(test);
				} else {
					if (y >= top && y < top + visible) {
						ctx.text(this.font, cur.toString(), x, y, color);
					}
					y += 11;
					contentH += 11;
					cur.setLength(0);
					cur.append(word);
				}
			}
			if (cur.length() > 0) {
				if (y >= top && y < top + visible) {
					ctx.text(this.font, cur.toString(), x, y, color);
				}
				y += 11;
				contentH += 11;
			}
		}

		if (contentH > visible) {
			int trackX = (this.width + panelW) / 2 - 4;
			int thumbH = Math.max(10, (int) ((float) visible / contentH * (visible - 4)));
			int maxScroll = contentH - visible;
			int thumbY = top + (maxScroll > 0 ? (int) ((float) scrollY / maxScroll * (visible - thumbH)) : 0);
			ctx.fill(trackX, top, trackX + 3, top + visible, 0xFF3A4048);
			ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF9AA0A8);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
	}
}
