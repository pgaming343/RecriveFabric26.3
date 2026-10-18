package com.recrive.ui;

import com.recrive.RecriveConfig;

import com.recrive.sync.RcloneBackend;
import com.recrive.sync.SyncProgress;
import com.recrive.sync.WorldSyncManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ☁ Recrive — Punchy-style layout: light header bar, left tab sidebar
 * (Worlds / Account / Settings), dark content panel, light-gray buttons with
 * green accents for primary actions.
 */
public class RecriveScreen extends Screen {
	private static final int HEADER_Y = 8, HEADER_H = 30;
	private static final int PANEL_TOP = 46;
	private static final int SB_X = 24, SB_W = 110;
	private static final int ROW_H = 66;
	private static final String[] TABS = {"Worlds", "Account", "Settings"};

	private final Screen parent;
	private final WorldSyncManager mgr = WorldSyncManager.get();
	private final RecriveConfig cfg = mgr.config();
	private List<WorldSyncManager.WorldEntry> worlds = List.of();
	private List<String> remotes = List.of();
	private int page = 0;
	private int cat = 0;
	private int rowsPerPage = 5;
	/** Ticks counter for periodic refresh during background operations. */
	private int tickCounter = 0;
	private boolean rcloneOk = true;

	/** Short-lived rclone query cache so wheel-scroll rebuilds stay instant. */
	private static List<String> remotesCache = null;
	private static boolean remotesCacheOk = true;
	private static long remotesCacheAt = 0L;

	/**
	 * Drops the cached remote list so the next screen build re-reads rclone.
	 * MUST be called after adding/removing an account, otherwise the refresh
	 * shows stale data until the 4 s cache expires (which is why Remove used
	 * to need two clicks and only updated after switching tabs).
	 */
	public static void invalidateRemotesCache() {
		remotesCache = null;
	}

	public RecriveScreen(Screen parent) {
		this(parent, 0, 0);
	}

	public RecriveScreen(Screen parent, int page) {
		this(parent, page, 0);
	}

	public RecriveScreen(Screen parent, int page, int cat) {
		super(Component.literal("☁ Recrive — Cloud Sync"));
		this.parent = parent;
		this.page = page;
		this.cat = cat;
	}

	private void refresh() {
		if (this.minecraft != null) this.minecraft.setScreenAndShow(new RecriveScreen(parent, page, cat));
	}

	private int contentX() {
		return SB_X + SB_W + 14;
	}

	private int contentR() {
		return this.width - 16;
	}

	private int contentW() {
		return contentR() - contentX();
	}

	private int innerX() {
		return contentX() + 8;
	}

	private int innerW() {
		return Math.max(60, contentW() - 16);
	}

	private int rowTop() {
		return PANEL_TOP + 22;
	}

	private int rowsBottom() {
		return this.height - 56;
	}

	private int bottomRowY() {
		return this.height - 40;
	}

	/** Greedy word-wrap, capped at maxLines (the last line is width-truncated). */
	private List<String> wrap(String s, int width, int maxLines) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : s.split(" ")) {
			String test = cur.length() == 0 ? word : cur + " " + word;
			if (this.font.width(test) <= width) {
				cur.setLength(0);
				cur.append(test);
			} else {
				out.add(cur.toString());
				cur.setLength(0);
				cur.append(word);
				if (out.size() >= maxLines) break;
			}
		}
		if (out.size() < maxLines && cur.length() > 0) out.add(cur.toString());
		if (out.size() > maxLines) out = out.subList(0, maxLines);
		if (!out.isEmpty()) {
			int lastIdx = out.size() - 1;
			out.set(lastIdx, this.font.plainSubstrByWidth(out.get(lastIdx), width));
		}
		return out;
	}

	@Override
	protected void init() {
		worlds = mgr.scanWorlds();
		long now = System.currentTimeMillis();
		if (remotesCache == null || now - remotesCacheAt > 4000L) {
			remotes = RcloneBackend.listRemotes();
			rcloneOk = RcloneBackend.available();
			remotesCache = remotes;
			remotesCacheOk = rcloneOk;
			remotesCacheAt = now;
		} else {
			remotes = remotesCache;
			rcloneOk = remotesCacheOk;
		}
		rowsPerPage = Math.max(2, Math.min(8, (rowsBottom() - rowTop()) / ROW_H));

		// ── Auto-select first remote if none selected ──
		if ((cfg.remote == null || cfg.remote.isBlank()) && !remotes.isEmpty()) {
			cfg.remote = remotes.get(0);
			cfg.save();
		}

		// ── Sidebar tabs (selected = green, rest light punchy-gray) ──
		for (int i = 0; i < TABS.length; i++) {
			int ty = PANEL_TOP + 10 + i * 26;
			if (i == cat) {
				this.addRenderableWidget(FlatButton.green(this.font, SB_X, ty, SB_W, 20, Component.literal(TABS[i]), () -> {}));
			} else {
				final int target = i;
				FlatButton t = FlatButton.flat(this.font, SB_X, ty, SB_W, 20, Component.literal(TABS[i]), () -> {
					cat = target;
					refresh();
				});
				t.setTooltip(Tooltip.create(Component.literal(switch (i) {
					case 0 -> "Per-world sync toggles, sync now and restore.";
					case 1 -> "Connect, switch or remove Google accounts.";
					default -> "Auto-sync interval and backup retention.";
				})));
				this.addRenderableWidget(t);
			}
		}

		int ix = innerX(), iw = innerW();

		// ── Done (green, bottom-right of the content panel) ──
		this.addRenderableWidget(FlatButton.green(this.font, contentR() - 96, bottomRowY(), 88, 20,
				Component.literal("Done"), this::onClose));
		// ── Info button (small "ℹ" square in the header, top-right) ──
		FlatButton infoBtn = FlatButton.flat(this.font, this.width - 36, HEADER_Y + 5, 22, 20,
				Component.literal("ℹ"), () -> {
					if (this.minecraft != null) this.minecraft.setScreenAndShow(new RecriveInfoScreen(this));
				});
		infoBtn.setTooltip(Tooltip.create(Component.literal("How to use Recrive & safety tips")));
		this.addRenderableWidget(infoBtn);

		// ── Worlds tab ──
		if (cat == 0) {
			int maxScroll = Math.max(0, worlds.size() - rowsPerPage);
			page = Math.max(0, Math.min(page, maxScroll));
			int from = page, to = Math.min(worlds.size(), page + rowsPerPage);
			int syncX = ix + iw - 196, restoreX = ix + iw - 100;
			for (int i = from; i < to; i++) {
				WorldSyncManager.WorldEntry w = worlds.get(i);
				String name = w.name();
				boolean on = mgr.isToggled(name);
				int ry = rowTop() + (i - from) * ROW_H;

				ToggleSwitch toggle = new ToggleSwitch(ix, ry, 46, 20, on, () -> {
					mgr.setToggled(name, !mgr.isToggled(name));
					refresh();
				});
				toggle.setTooltip(Tooltip.create(Component.literal(on
						? "ON — .zip backup every " + cfg.intervalMin + " min. Click to stop."
						: "OFF. Click to start continuous .zip backup to Drive.")));
				this.addRenderableWidget(toggle);

				FlatButton sync = FlatButton.flat(this.font, syncX, ry + 22, 92, 20,
						Component.literal("Sync now"), () -> mgr.syncNow(name));
				sync.setTooltip(Tooltip.create(Component.literal("Pack into one .zip and upload now.")));
				this.addRenderableWidget(sync);

				if (mgr.isBusy(name)) {
					FlatButton cancel = FlatButton.flat(this.font, restoreX, ry + 22, 92, 20,
							Component.literal("✕ Cancel"), () -> mgr.cancel(name));
					cancel.setTooltip(Tooltip.create(Component.literal("Stop the running upload/download for this world.")));
					this.addRenderableWidget(cancel);
				} else {
					FlatButton restore = FlatButton.flat(this.font, restoreX, ry + 22, 92, 20,
							Component.literal("Restore"), () -> mgr.restore(name));
					restore.setTooltip(Tooltip.create(Component.literal("Download the .zip from Drive and unpack. Local .bak copy first.")));
					this.addRenderableWidget(restore);
				}
			}
		}
		// ── Account tab ──
		else if (cat == 1) {
			int ry = rowTop();
			if (remotes.isEmpty()) {
				if (mgr.isConnecting()) {
					FlatButton wait = FlatButton.flat(this.font, ix, ry, iw, 20,
							Component.literal("⏳ " + mgr.connectingStatus()), () -> {});
					wait.active = false;
					this.addRenderableWidget(wait);
				} else {
					FlatButton connect = FlatButton.green(this.font, ix, ry, iw, 20,
							Component.literal("🔗 Connect Google Account"), () -> {
								mgr.connectAccount(() -> {
									invalidateRemotesCache();
									if (this.minecraft != null) this.minecraft.execute(this::refresh);
								});
								refresh();
							});
					connect.setTooltip(Tooltip.create(Component.literal(
							"Opens your browser to sign in with Google. Uses rclone for secure OAuth.")));
					this.addRenderableWidget(connect);
				}
			} else {
				String acct = cfg.remote.isBlank() ? remotes.get(0) : cfg.remote;
				int cw3 = (iw - 12) / 3;

				FlatButton switcher = FlatButton.flat(this.font, ix, ry, cw3, 20,
						Component.literal("Account: " + acct), () -> {
							int i = remotes.indexOf(cfg.remote);
							cfg.remote = remotes.get((i + 1) % remotes.size());
							cfg.save();
							refresh();
						});
				switcher.setTooltip(Tooltip.create(Component.literal("Cycle through every connected account.")));
				this.addRenderableWidget(switcher);

				FlatButton add = FlatButton.green(this.font, ix + cw3 + 6, ry, cw3, 20, Component.literal("+ Add"), () -> {
					mgr.connectAccount(() -> {
						invalidateRemotesCache();
						if (this.minecraft != null) this.minecraft.execute(this::refresh);
					});
					refresh();
				});
				add.setTooltip(Tooltip.create(Component.literal("Connect another Google account via browser OAuth.")));
				this.addRenderableWidget(add);

				FlatButton remove = FlatButton.flat(this.font, ix + 2 * (cw3 + 6), ry, cw3, 20, Component.literal("− Remove"), () -> {
					if (mgr.isConnecting()) return;
					String doomed = cfg.remote.isBlank() ? acct : cfg.remote;
					mgr.removeAccount(doomed, () -> {
						invalidateRemotesCache();
						if (this.minecraft != null) this.minecraft.execute(this::refresh);
					});
					refresh();
				});
				remove.setTooltip(Tooltip.create(Component.literal(
						"Disconnect the selected Google account. Worlds already on Drive stay there.")));
				this.addRenderableWidget(remove);
			}
		}
		// ── Settings tab (punchy rows: title + description + option buttons) ──
		else {
			int bw = Math.max(56, (iw - 12) / 4);

			// Auto-sync interval: one button per interval value.
			int[] opts = {5, 10, 30, 60};
			for (int k = 0; k < opts.length; k++) {
				final int value = opts[k];
				int bx = ix + k * (bw + 4);
				FlatButton b = (cfg.intervalMin == value)
						? FlatButton.green(this.font, bx, rowTop() + 34, bw, 20, Component.literal(value + " min"), () -> {})
						: FlatButton.flat(this.font, bx, rowTop() + 34, bw, 20, Component.literal(value + " min"), () -> {
							// mgr.setIntervalMin saves config AND restarts the daemon
							// so the new interval takes effect immediately (Bug 1 fix).
							mgr.setIntervalMin(value);
							refresh();
						});
				b.setTooltip(Tooltip.create(Component.literal("Auto-backup every " + value + " minutes.")));
				this.addRenderableWidget(b);
			}

			// Backups kept on Drive: one button per retention count.
			int[] keep = {1, 2, 3, 5};
			for (int k = 0; k < keep.length; k++) {
				final int value = keep[k];
				int bx = ix + k * (bw + 4);
				FlatButton b = (cfg.bakKeep == value)
						? FlatButton.green(this.font, bx, rowTop() + 106, bw, 20, Component.literal("" + value), () -> {})
						: FlatButton.flat(this.font, bx, rowTop() + 106, bw, 20, Component.literal("" + value), () -> {
							cfg.bakKeep = value;
							cfg.save();
							refresh();
						});
				b.setTooltip(Tooltip.create(Component.literal("Keep " + value + " .zip backup" + (value > 1 ? "s" : "") + " after each sync.")));
				this.addRenderableWidget(b);
			}
		}
	}

	/** Mouse-wheel scrolling of the world list (Worlds tab). */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (cat == 0 && !worlds.isEmpty()) {
			int maxScroll = Math.max(0, worlds.size() - rowsPerPage);
			if (maxScroll > 0) {
				page = Math.max(0, Math.min(maxScroll, page - (int) Math.signum(scrollY)));
				refresh();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void tick() {
		super.tick();
		tickCounter++;
		// Auto-refresh every 2 seconds while connecting or while any operation
		// runs, so status text and Cancel/Restore buttons stay accurate.
		if ((mgr.isConnecting() || !mgr.allProgress().isEmpty()) && tickCounter % 40 == 0) {
			refresh();
		}
	}

	/** Replaces the layered blur backdrop with the Punchy-style chrome. */
	@Override
	public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0xF0101216);

		// Sidebar panel
		ctx.fill(SB_X - 8, PANEL_TOP, SB_X + SB_W + 8, this.height - 12, 0xFF3A4048);
		ctx.fill(SB_X - 7, PANEL_TOP + 1, SB_X + SB_W + 7, this.height - 13, 0xF0171A20);

		// Content panel
		ctx.fill(contentX() - 8, PANEL_TOP, contentR(), this.height - 12, 0xFF3A4048);
		ctx.fill(contentX() - 7, PANEL_TOP + 1, contentR() - 1, this.height - 13, 0xF01B1E24);

		// Header bar (light, bevelled)
		ctx.fill(8, HEADER_Y, this.width - 8, HEADER_Y + HEADER_H, 0xFF000000);
		ctx.fill(9, HEADER_Y + 1, this.width - 9, HEADER_Y + 3, 0xFFE4E4E4);
		ctx.fill(9, HEADER_Y + 1, 11, HEADER_Y + HEADER_H - 1, 0xFFE4E4E4);
		ctx.fill(9, HEADER_Y + 3, this.width - 9, HEADER_Y + HEADER_H - 4, 0xFFC6C6C6);
		ctx.fill(9, HEADER_Y + HEADER_H - 4, this.width - 9, HEADER_Y + HEADER_H - 1, 0xFF909090);
		ctx.fill(this.width - 11, HEADER_Y + 1, this.width - 9, HEADER_Y + HEADER_H - 1, 0xFF909090);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
		super.extractRenderState(ctx, mouseX, mouseY, delta);

		ctx.centeredText(this.font, this.title, this.width / 2, HEADER_Y + (HEADER_H - 9) / 2, 0xFFFFFFFF);

		int ix = innerX(), iw = innerW();

		if (cat == 0) {
			String status = "status: " + mgr.status() + "   •   worlds: " + worlds.size();
			if (!rcloneOk) status += "   •   ⚠ rclone missing";
			if (remotes.isEmpty() && !mgr.isConnecting()) status += "   •   ⚠ no Google account";
			ctx.text(this.font, this.font.plainSubstrByWidth(status, iw), ix, PANEL_TOP + 8, 0xFF9AA0A8);

			int maxScroll = Math.max(0, worlds.size() - rowsPerPage);
			int from = Math.max(0, Math.min(page, maxScroll)), to = Math.min(worlds.size(), from + rowsPerPage);
			int syncX = ix + iw - 196;
			for (int i = from; i < to; i++) {
				WorldSyncManager.WorldEntry w = worlds.get(i);
				int ry = rowTop() + (i - from) * ROW_H;

				int n = 0;
				for (String piece : wrap(w.name(), iw - 64, 2)) {
					ctx.text(this.font, piece, ix + 56, ry + 2 + n * 10, 0xFFE8E8E8);
					n++;
				}

				String cap = "AutoSync";
				ctx.text(this.font, cap, ix + (46 - Math.min(46, this.font.width(cap))) / 2, ry + 24, 0xFFC8CCD2);

				int ly = ry + 44;
				SyncProgress p = mgr.progress(w.name());
				if (p != null) {
					ProgressBarRenderer.drawRow(ctx, this.font, ix, ly - 2, iw, p);
				} else {
					String last = cfg.lastSync.getOrDefault(w.name(), "never");
					String line = "(" + w.size() + ")  last sync: " + last
							+ ("never".equals(last) ? "" : "  ✔ on Drive: " + RcloneBackend.remoteZipPath(cfg.remote, w.name()));
					n = 0;
					for (String piece : wrap(line, iw, 2)) {
						ctx.text(this.font, piece, ix, ly + n * 10, 0xFF8A8F96);
						n++;
					}
				}
			}
			// scrollbar indicator
			if (maxScroll > 0) {
				int trackH = rowsBottom() - rowTop() + 10;
				int trackY = rowTop() - 2;
				ctx.fill(ix + iw + 2, trackY, ix + iw + 5, trackY + trackH, 0xFF5A616B);
				float frac = (float) page / Math.max(1, maxScroll);
				int thumbH = Math.max(8, (int) ((float) rowsPerPage / (rowsPerPage + maxScroll) * trackH));
				int thumbY = trackY + (int) (frac * (trackH - thumbH));
				ctx.fill(ix + iw + 2, thumbY, ix + iw + 5, thumbY + thumbH, 0xFF9AA0A8);
			}
			if (!mgr.lastMessage().isEmpty()) {
				ctx.text(this.font, this.font.plainSubstrByWidth(mgr.lastMessage(), iw),
						ix, this.height - 52, 0xFFCCCCCC);
			}
		} else if (cat == 1) {
			if (mgr.isConnecting()) {
				int n = 0;
				for (String piece : wrap(mgr.connectingStatus(), iw, 2)) {
					ctx.text(this.font, piece, ix, rowTop() + 28 + n * 10, 0xFFFFE082);
					n++;
				}
				String url = RcloneBackend.lastAuthUrl();
				if (!url.isBlank()) {
					for (String piece : wrap("No browser? Visit: " + url, iw, 2)) {
						ctx.text(this.font, piece, ix, rowTop() + 52 + n * 10, 0xFF9BD1FF);
						n++;
					}
				}
			} else if (remotes.isEmpty()) {
				ctx.text(this.font, "No Google account connected yet.", ix, rowTop() + 28, 0xFF9AA0A8);
			} else {
				int n = 0;
				for (String piece : wrap("Switch, add or remove a Google account. Syncs go to Drive folder MC-World-Sync.", iw, 2)) {
					ctx.text(this.font, piece, ix, rowTop() + 28 + n * 10, 0xFF9AA0A8);
					n++;
				}
			}
		} else {
			ctx.text(this.font, "Auto-sync interval", ix, rowTop(), 0xFFE8E8E8);
			int n = 0;
			for (String piece : wrap("How often toggled worlds are .zip-backed up to Drive.", iw, 2)) {
				ctx.text(this.font, piece, ix, rowTop() + 11 + n * 10, 0xFF8A8F96);
				n++;
			}
			ctx.fill(ix, rowTop() + 64, ix + iw, rowTop() + 65, 0xFF5A616B);

			ctx.text(this.font, ".zip backups kept on Drive", ix, rowTop() + 72, 0xFFE8E8E8);
			n = 0;
			for (String piece : wrap("Old backups beyond this count are pruned after each successful sync.", iw, 2)) {
				ctx.text(this.font, piece, ix, rowTop() + 83 + n * 10, 0xFF8A8F96);
				n++;
			}
			ctx.fill(ix, rowTop() + 136, ix + iw, rowTop() + 137, 0xFF5A616B);

			ctx.text(this.font, this.font.plainSubstrByWidth(
					"rclone: " + (rcloneOk ? "✓ available" : "⚠ missing — install rclone and restart"), iw),
					ix, rowTop() + 148, 0xFF9AA0A8);
			ctx.text(this.font, this.font.plainSubstrByWidth(
					"account: " + (remotes.isEmpty() ? "none" : cfg.remote), iw),
					ix, rowTop() + 164, 0xFF9AA0A8);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
	}
}
