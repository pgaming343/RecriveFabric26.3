package com.recrive.sync;

/**
 * Live, thread-safe snapshot of one world's sync/restore operation.
 * Written by the rclone worker thread, read every frame by the UI / HUD.
 */
public final class SyncProgress {
	public enum Phase { PREPARING, TRANSFERRING, VERIFYING, DONE, FAILED }

	public final String world;
	/** "Uploading" or "Restoring". */
	public final String action;
	private volatile Phase phase = Phase.PREPARING;
	private volatile int percent = 0;
	private volatile String transferred = "";
	private volatile String total = "";
	private volatile String speed = "";
	private volatile String eta = "";
	private volatile String message = "Starting…";
	private volatile long finishedAt = 0L;
	private volatile Runnable canceller = null;
	private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

	public SyncProgress(String world, String action) {
		this.world = world;
		this.action = action;
	}

	public void stats(int percent, String transferred, String total, String speed, String eta) {
		this.phase = Phase.TRANSFERRING;
		this.percent = Math.max(0, Math.min(100, percent));
		this.transferred = transferred;
		this.total = total;
		this.speed = speed;
		this.eta = eta;
		// At 100% rclone is finalizing on Drive (can take minutes with no new
		// stats) — say so instead of looking stuck.
		this.message = this.percent >= 100
				? transferred + " / " + total + "  •  finalizing on Drive…"
				: transferred + " / " + total + "  •  " + speed + "  •  ETA " + eta;
	}

	/** Lets the worker thread be killed from the UI (Cancel button). */
	public void setCanceller(Runnable r) {
		this.canceller = r;
	}

	public void requestCancel() {
		cancelled.set(true);
		Runnable r = canceller;
		if (r != null) r.run();
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	public void phase(Phase p, String msg) {
		this.phase = p;
		this.message = msg;
		if (p == Phase.DONE) this.percent = 100;
		if (p == Phase.DONE || p == Phase.FAILED) this.finishedAt = System.currentTimeMillis();
	}

	public Phase phase() { return phase; }
	public int percent() { return percent; }
	public String message() { return message; }
	public String transferred() { return transferred; }
	public String total() { return total; }
	public String speed() { return speed; }
	public String eta() { return eta; }

	public boolean active() {
		return phase != Phase.DONE && phase != Phase.FAILED;
	}

	/** Finished operations stay visible for a while so users can read the result. */
	public boolean expired(long keepMs) {
		return !active() && System.currentTimeMillis() - finishedAt > keepMs;
	}
}
