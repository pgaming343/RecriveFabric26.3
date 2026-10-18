package com.recrive.ui;

import com.recrive.sync.SyncProgress;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws Recrive's sync progress bars — used both inside the Cloud Sync screen
 * (one bar per world row) and as an overlay while the pause menu is open.
 * Nothing is ever drawn over live gameplay.
 */
public final class ProgressBarRenderer {
	private static final int BG = 0xCC101418;
	private static final int BORDER = 0xFF3A4048;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int SUBTEXT = 0xFFB8C0CC;

	private ProgressBarRenderer() {
	}

	/** Top + bottom colour of the fill, per phase. */
	private static int[] colours(SyncProgress.Phase phase) {
		return switch (phase) {
			case PREPARING -> new int[]{0xFF8B949E, 0xFF5A626B};
			case TRANSFERRING -> new int[]{0xFF4FC3F7, 0xFF1E88E5};
			case VERIFYING -> new int[]{0xFFB388FF, 0xFF7C4DFF};
			case DONE -> new int[]{0xFF66BB6A, 0xFF2E7D32};
			case FAILED -> new int[]{0xFFEF5350, 0xFFC62828};
		};
	}

	/**
	 * Draws a bar with the percentage centred inside it.
	 * Preparing / verifying phases have no real percentage, so they show an
	 * animated sliding segment instead.
	 */
	public static void drawBar(GuiGraphicsExtractor ctx, Font tr, int x, int y, int w, int h, SyncProgress p) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
		ctx.fill(x, y, x + w, y + h, BG);
		int[] c = colours(p.phase());
		boolean indeterminate = p.phase() == SyncProgress.Phase.PREPARING
				|| p.phase() == SyncProgress.Phase.VERIFYING;
		String label;
		if (indeterminate) {
			int seg = Math.max(12, w / 4);
			long t = System.currentTimeMillis() % 1400L;
			int off = (int) ((w + seg) * t / 1400L) - seg;
			int sx = Math.max(x, x + off), ex = Math.min(x + w, x + off + seg);
			if (ex > sx) ctx.fillGradient(sx, y, ex, y + h, c[0], c[1]);
			label = p.phase() == SyncProgress.Phase.VERIFYING ? "verifying…" : "preparing…";
		} else {
			int fw = (int) (w * (p.percent() / 100.0));
			if (fw > 0) ctx.fillGradient(x, y, x + fw, y + h, c[0], c[1]);
			label = p.phase() == SyncProgress.Phase.FAILED ? "failed" : p.percent() + "%";
		}
		int ty = y + (h - 8) / 2 + 1;
		ctx.centeredText(tr, label, x + w / 2, ty, TEXT);
	}

	/** Bar + one line of detail text underneath (used per world row in the screen). */
	public static void drawRow(GuiGraphicsExtractor ctx, Font tr, int x, int y, int w, SyncProgress p) {
		drawBar(ctx, tr, x, y, w, 10, p);
		String msg = p.action + ": " + p.message();
		msg = tr.plainSubstrByWidth(msg, w);
		ctx.centeredText(tr, msg, x + w / 2, y + 12, colourFor(p));
	}

	private static int colourFor(SyncProgress p) {
		return switch (p.phase()) {
			case DONE -> 0xFF7EE787;
			case FAILED -> 0xFFFF7B72;
			default -> SUBTEXT;
		};
	}

	/**
	 * Pause-menu overlay: compact card(s) in the top-right corner while syncs
	 * run. Called at the END of GameMenuScreen.render — i.e. in the screen
	 * layer, AFTER the pause background blur is drawn — so the cards stay
	 * crisp. (Anything drawn in the HUD layer gets blurred by the pause menu,
	 * which is why the old overlay looked smeary.) Never called during live
	 * gameplay.
	 *
	 * @param screenWidth right edge anchor (cards sit below the slim "☁ Sync"
	 *                    button at y 8–28)
	 */
	public static void renderPauseCards(GuiGraphicsExtractor ctx, int screenWidth) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		List<SyncProgress> all = com.recrive.sync.WorldSyncManager.get().allProgress();
		if (all.isEmpty()) return;
		Font tr = mc.font;
		int w = 160, cardH = 34;
		int x = screenWidth - w - 6;
		int y = 34; // below the "☁ Sync" button (y 8–28) + margin
		for (SyncProgress p : all) {
			ctx.fill(x - 4, y - 4, x + w + 4, y + cardH - 2, 0xDD000000); // near-opaque: stays crisp over the blurred pause backdrop
			String head = "☁ " + p.action + " " + p.world;
			ctx.text(tr, tr.plainSubstrByWidth(head, w), x, y, TEXT);
			drawBar(ctx, tr, x, y + 11, w, 9, p);
			String sub = p.phase() == SyncProgress.Phase.TRANSFERRING
					? p.transferred() + " / " + p.total() + " • " + p.speed()
					: p.message();
			ctx.text(tr, tr.plainSubstrByWidth(sub, w), x, y + 22, colourFor(p));
			y += cardH + 4;
		}
	}
}
