package com.recrive.sync;

import com.recrive.RecriveConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Scans saves/, owns the auto-sync daemon (one pass every intervalMin for
 * every world toggled ON). When the target world is currently open (singleplayer),
 * the daemon first flushes Minecraft's saves to disk so the zip captures a
 * consistent state — rather than deferring until the world is closed.
 * Player data (players/, stats/, advancements/) lives inside the world folder,
 * so syncing the folder syncs worlds + player data together.
 * NOTE: restore() still refuses to overwrite a live world (that would corrupt it).
 */
public final class WorldSyncManager {
	public record WorldEntry(String name, Path dir, long bytes) {
		public String size() {
			return RcloneBackend.humanSize(bytes);
		}
	}

	private static final WorldSyncManager INSTANCE = new WorldSyncManager();
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicReference<String> status = new AtomicReference<>("idle");
	private volatile RecriveConfig config;
	private Thread daemon;
	private volatile boolean connecting = false;
	private volatile String connectingStatus = "";
	/** Live progress per world (active + recently finished operations). */
	private final Map<String, SyncProgress> progress = new ConcurrentHashMap<>();
	private volatile String lastMessage = "";

	private WorldSyncManager() {
	}

	public static WorldSyncManager get() {
		return INSTANCE;
	}

	public void init(RecriveConfig config) {
		this.config = config;
		startDaemon();
	}

	public RecriveConfig config() {
		return config;
	}

	public void logListener(Consumer<String> l) {
		listeners.add(l);
	}

	public String status() {
		return status.get();
	}

	/** Most recent log line (shown at the bottom of the Cloud Sync screen). */
	public String lastMessage() {
		return lastMessage;
	}

	/** Progress for a world, or null if nothing is running / recently finished. */
	public SyncProgress progress(String world) {
		SyncProgress p = progress.get(world);
		if (p != null && p.expired(15_000)) {
			progress.remove(world, p);
			return null;
		}
		return p;
	}

	/** All active or recently finished operations (for the in-game HUD). */
	public List<SyncProgress> allProgress() {
		List<SyncProgress> out = new ArrayList<>();
		for (String k : progress.keySet()) {
			SyncProgress p = progress(k);
			if (p != null) out.add(p);
		}
		return out;
	}

	public boolean isBusy(String world) {
		SyncProgress p = progress.get(world);
		return p != null && p.active();
	}

	/** Cancels a running operation (Cancel button). The worker reports it as failed-cancelled. */
	public void cancel(String world) {
		SyncProgress p = progress.get(world);
		if (p != null && p.active()) {
			log("cancelling '" + world + "'…");
			p.requestCancel();
		}
	}

	/** Appends a line to logs/recrive-debug.log (full error transcripts for bug reports). */
	public synchronized void debugLog(String msg) {
		try {
			java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getGameDir().resolve("logs").resolve("recrive-debug.log");
			Files.createDirectories(f.getParent());
			String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] " + msg + "\n";
			Files.write(f, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	/** Atomically registers a new operation, or returns null if the world is already busy. */
	private synchronized SyncProgress claim(String world, String action) {
		if (isBusy(world)) return null;
		SyncProgress p = new SyncProgress(world, action);
		progress.put(world, p);
		return p;
	}

	private void log(String msg) {
		String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg;
		lastMessage = line;
		System.out.println("[recrive] " + msg);
		for (Consumer<String> l : listeners) l.accept(line);
	}

	public Path savesDir() {
		Minecraft c = Minecraft.getInstance();
		if (c != null && c.gameDirectory != null) return c.gameDirectory.toPath().resolve("saves");
		return Path.of(System.getProperty("user.home"), ".minecraft", "saves");
	}

	/** Every subfolder of saves/ containing level.dat — i.e. all worlds. */
	public List<WorldEntry> scanWorlds() {
		List<WorldEntry> out = new ArrayList<>();
		Path saves = savesDir();
		if (!Files.isDirectory(saves)) return out;
		List<Path> kids;
		try (var stream = Files.list(saves)) {
			kids = stream.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
		} catch (Exception e) {
			return out;
		}
		for (Path kid : kids) {
			if (Files.isRegularFile(kid.resolve("level.dat")) || Files.isRegularFile(kid.resolve("level.dat_old"))) {
				out.add(new WorldEntry(kid.getFileName().toString(), kid, RcloneBackend.dirSize(kid)));
			}
		}
		return out;
	}

	public boolean isToggled(String name) {
		return config != null && Boolean.TRUE.equals(config.toggles.get(name));
	}

	public void setToggled(String name, boolean on) {
		if (config == null) return;
		config.toggles.put(name, on);
		config.save();
		log((on ? "Auto-sync ON for " : "Auto-sync OFF for ") + name);
	}

	/** Name of the currently open singleplayer world, or null. */
	public static String openWorldName() {
		try {
			Minecraft c = Minecraft.getInstance();
			// getSingleplayerServer() is non-null exactly when hosting/playing a singleplayer world.
			if (c != null && c.getSingleplayerServer() != null) {
				String name = c.getSingleplayerServer().getWorldData().getLevelName();
				return name;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	public void syncNow(String name) {
		// upload() calls claim() atomically; no separate isBusy check needed here
		// (avoids TOCTOU) — it will log and return if the world is already busy.
		new Thread(() -> upload(name), "recrive-sync-" + name).start();
	}

	/** Whether a Google account connection is in progress. */
	public boolean isConnecting() {
		return connecting;
	}

	/** Human-readable status of the current connection attempt. */
	public String connectingStatus() {
		return connectingStatus;
	}

	/**
	 * Starts an in-game Google account connection flow on a background thread.
	 * Opens the user's browser for Google OAuth via {@code rclone authorize "drive"}.
	 * On success, updates config.remote to the new remote name and calls onDone.
	 */
	public void connectAccount(Runnable onDone) {
		// Claim the flow SYNCHRONOUSLY. Previously `connecting` was set inside the
		// worker thread, so a quick second click slipped past this guard and started
		// a second `rclone authorize` → two browser sign-ins. Setting it here closes
		// that race: only the first click can ever start the flow.
		synchronized (this) {
			if (connecting) {
				log("connect: already waiting for browser authorization.");
				return;
			}
			connecting = true;
		}
		new Thread(() -> {
			connectingStatus = "Step 1/2: opening browser — approve Google IN THAT TAB…";
			log("Opening browser — approve the Google consent screen in the opened tab (don't sign in via a separate tab).");
			try {
				String remoteName = RcloneBackend.nextRemoteName();
				connectingStatus = "Step 2/2: waiting for Google approval (link also shown below)…";
				RcloneBackend.Result r = RcloneBackend.createRemote(remoteName);
				if (r.ok()) {
					config.remote = remoteName;
					config.save();
					connectingStatus = "Connected as '" + remoteName + "'!";
					log("Google account connected as remote '" + remoteName + "'. Sync is now available.");
				} else {
					connectingStatus = "Connection failed: " + r.detail();
					log("connect FAILED: " + r.detail());
					debugLog("connect FAILED for '" + remoteName + "': " + r.detail());
					log("Full transcript in logs/recrive-debug.log — send it for help.");
				}
			} catch (Exception e) {
				connectingStatus = "Error: " + e.getMessage();
				log("connect error: " + e.getMessage());
				debugLog("connect error: " + e);
			} finally {
				connecting = false;
				if (onDone != null) onDone.run();
			}
		}, "recrive-connect").start();
	}

	/**
	 * Removes a connected Google account (rclone remote). If it was the
	 * selected account, falls back to another remote or none.
	 */
	public void removeAccount(String name, Runnable onDone) {
		new Thread(() -> {
			try {
				if (name == null || name.isBlank()) {
					log("remove: no account selected.");
					return;
				}
				log("removing account '" + name + "'…");
				RcloneBackend.Result r = RcloneBackend.deleteRemote(name);
				if (!r.ok()) {
					log("remove FAILED: " + r.detail());
					return;
				}
				String clean = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
				if (clean.equals(config.remote)) {
					java.util.List<String> rest = RcloneBackend.listRemotes();
					rest.removeIf(r2 -> r2.replaceAll(":$", "").equals(clean));
					config.remote = rest.isEmpty() ? "" : rest.get(0);
					config.save();
				}
				log("account '" + clean + "' removed."
						+ (config.remote.isBlank() ? " No Google account selected." : " Now using '" + config.remote + "'."));
			} finally {
				if (onDone != null) onDone.run();
			}
		}, "recrive-remove-account").start();
	}

	public void restore(String name) {
		new Thread(() -> {
			Path dir = savesDir().resolve(name);
			if (name.equals(openWorldName())) {
				log("restore: close the world '" + name + "' first — refusing to overwrite an open world.");
				return;
			}
			String remote = config.remote;
			if (remote == null || remote.isBlank() || remote.startsWith("(")) {
				log("restore: no Google account selected — click 'Connect Google Account' first.");
				return;
			}
			SyncProgress prog = claim(name, "Restoring");
			if (prog == null) {
				log("restore: '" + name + "' is busy — wait for the current operation to finish.");
				return;
			}
			Path zip = null;
			try {
				if (Files.isDirectory(dir)) {
					status.set("backing up " + name);
					prog.phase(SyncProgress.Phase.PREPARING, "Making local backup…");
					Path bak = RcloneBackend.backupLocal(dir);
					log("local backup → " + bak.getFileName());
					RcloneBackend.clearDir(dir);
				} else {
					Files.createDirectories(dir);
				}
				zip = Files.createTempFile(name + "-restore-", ".zip");
				status.set("downloading " + name);
				prog.phase(SyncProgress.Phase.PREPARING, "Downloading .zip from Drive…");
				RcloneBackend.Result r = RcloneBackend.downloadZip(remote, name, zip, prog);
				if (!r.ok()) {
					if (r.detail().contains("cancelled by user")) {
						prog.phase(SyncProgress.Phase.FAILED, "✖ Cancelled");
						log("restore of '" + name + "' cancelled.");
					} else {
						prog.phase(SyncProgress.Phase.FAILED, "✖ Download failed: " + shortErr(r.detail()));
						log("restore FAILED for " + name + ": " + r.detail());
						debugLog("restore FAILED for " + name + ": " + r.detail());
					}
					status.set("idle");
					return;
				}
				prog.phase(SyncProgress.Phase.PREPARING, "Unpacking…");
				RcloneBackend.unzipWorld(zip, dir);
				status.set("idle");
				prog.phase(SyncProgress.Phase.DONE, "✔ Restored from Drive");
				log("restored " + name + " from Drive");
			} catch (Exception e) {
				status.set("idle");
				prog.phase(SyncProgress.Phase.FAILED, "✖ Restore failed: " + shortErr(e.getMessage()));
				log("restore error for " + name + ": " + e.getMessage());
			} finally {
				if (zip != null) {
					try {
						Files.deleteIfExists(zip);
					} catch (Exception ignored) {
					}
				}
			}
		}, "recrive-restore-" + name).start();
	}

	private static String shortErr(String s) {
		if (s == null) return "";
		s = s.replaceAll("\\s+", " ").trim();
		return s.length() > 90 ? s.substring(s.length() - 90) : s;
	}

	/**
	 * After a verified upload, the world is safe on Drive — delete old local
	 * {@code <world>.bak-*} safety copies beyond {@code config.bakKeep}.
	 * Only ever runs on SUCCESS, so a failed sync never costs you a backup.
	 */
	private void pruneOldBackups(String name) {
		try {
			List<String> deleted = RcloneBackend.pruneBackups(savesDir(), name,
					config == null ? 1 : config.bakKeep);
			for (String d : deleted) log("cleaned old backup: " + d);
		} catch (Exception e) {
			log("backup cleanup skipped: " + e.getMessage());
		}
	}

	private void upload(String name) {
		Path dir = savesDir().resolve(name);
		if (!Files.isDirectory(dir)) {
			log("sync: world folder missing: " + name);
			return;
		}
		// If the world is currently open, force Minecraft to flush all dirty chunks
		// to disk before zipping, so we capture a consistent saved state.
		// (Restore is still blocked — see restore() — because overwriting live files
		//  would corrupt the running session.)
		if (name.equals(openWorldName())) {
			log("sync: '" + name + "' is open in-game — flushing world saves before backup…");
			try {
				Minecraft mc = Minecraft.getInstance();
				net.minecraft.server.MinecraftServer srv =
						mc != null ? mc.getSingleplayerServer() : null;
				if (srv != null) {
					CountDownLatch latch = new CountDownLatch(1);
					srv.execute(() -> {
						try {
							// flush chunks + player data to disk before zipping
							srv.saveEverything(true, true, false);
						} catch (Exception ignored) {
						} finally {
							latch.countDown();
						}
					});
					if (!latch.await(30, TimeUnit.SECONDS)) {
						log("sync: world save timed out — skipping '" + name + "' this cycle.");
						return;
					}
					log("sync: world saved — proceeding with backup of '" + name + "'.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log("sync: save flush error for '" + name + "': " + e.getMessage() + " — proceeding with best-effort backup.");
			}
			// Fall through to the normal zip → upload path below.
		}
		String remote = config.remote;
		if (remote == null || remote.isBlank() || remote.startsWith("(")) {
			log("sync: no Google account selected — click 'Connect Google Account' in ☁ Cloud Sync.");
			return;
		}
		SyncProgress prog = claim(name, "Uploading");
		if (prog == null) {
			log("sync: '" + name + "' is already syncing — please wait.");
			return;
		}
		prog.phase(SyncProgress.Phase.PREPARING, "Checking rclone…");
		if (!RcloneBackend.available()) {
			prog.phase(SyncProgress.Phase.FAILED, "✖ rclone not found");
			log("sync: rclone not found (~/bin/rclone or PATH). See recrive README.");
			return;
		}
		// ZIP-only sync: pack the world into ONE uncompressed .zip (no CPU
		// recompression — region files are already compressed) and upload that
		// single file at full line rate. No per-file Drive transactions.
		Path zip = null;
		try {
			status.set("packing " + name);
			prog.phase(SyncProgress.Phase.PREPARING, "Packing world into one .zip…");
			log("packing " + name + " …");
			zip = RcloneBackend.zipWorld(dir);
			log("packed " + RcloneBackend.humanSize(Files.size(zip)) + ", uploading…");
			status.set("uploading " + name);
			RcloneBackend.Result r = RcloneBackend.uploadZip(zip, remote, name, prog);
			if (!r.ok()) {
				status.set("idle");
				if (r.detail().contains("cancelled by user")) {
					prog.phase(SyncProgress.Phase.FAILED, "✖ Cancelled");
					log("sync of '" + name + "' cancelled.");
				} else {
					prog.phase(SyncProgress.Phase.FAILED, "✖ Upload failed: " + shortErr(r.detail()));
					log("sync FAILED for " + name + ": " + r.detail());
					debugLog("sync FAILED for " + name + ": " + r.detail());
				}
				return;
			}

			// Confirm the .zip really is on Drive (remote size must match).
			status.set("verifying " + name);
			prog.phase(SyncProgress.Phase.VERIFYING, "Verifying .zip on Google Drive…");
			RcloneBackend.Result v = RcloneBackend.verifyZip(zip, remote, name);
			status.set("idle");
			String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
			if (v.ok()) {
				config.lastSync.put(name, ts);
				config.save();
				prog.phase(SyncProgress.Phase.DONE, "✔ Verified on Drive — " + v.detail());
				log("synced " + name + " at " + ts + " — verified: " + v.detail()
						+ " (" + RcloneBackend.remoteZipPath(remote, name) + ")");
				pruneOldBackups(name);
			} else {
				prog.phase(SyncProgress.Phase.FAILED, "✖ Uploaded but verification failed");
				log("sync VERIFY FAILED for " + name + ": " + v.detail());
				debugLog("sync VERIFY FAILED for " + name + ": " + v.detail());
			}
		} catch (Exception e) {
			status.set("idle");
			prog.phase(SyncProgress.Phase.FAILED, "✖ Sync failed: " + shortErr(e.getMessage()));
			log("sync error for " + name + ": " + e.getMessage());
		} finally {
			if (zip != null) {
				try {
					Files.deleteIfExists(zip);
				} catch (Exception ignored) {
				}
			}
		}
	}

	/**
	 * Starts the auto-sync daemon if it is not already running.
	 * Synchronized to prevent two daemons being spawned concurrently.
	 */
	private synchronized void startDaemon() {
		if (daemon != null && daemon.isAlive()) return;
		daemon = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					int mins = Math.max(1, config.intervalMin);
					Thread.sleep(mins * 60L * 1000L);
				} catch (InterruptedException e) {
					// Either the mod is shutting down, or setIntervalMin() asked us
					// to restart with a new sleep duration. In both cases we stop.
					Thread.currentThread().interrupt();
					return;
				}
				// Bug 3 fix: a crash in one world's upload must NOT kill the daemon.
				for (WorldEntry w : scanWorlds()) {
					try {
						if (isToggled(w.name())) upload(w.name());
					} catch (Exception e) {
						log("auto-sync error for '" + w.name() + "': " + e.getMessage());
						debugLog("auto-sync uncaught error for '" + w.name() + "': " + e);
					}
				}
			}
		}, "recrive-daemon");
		daemon.setDaemon(true);
		daemon.start();
	}

	/**
	 * Interrupts the sleeping daemon so it stops immediately, then respawns
	 * it with the new {@code config.intervalMin}. The interrupted daemon
	 * exits cleanly (it just returns on InterruptedException).
	 * Called whenever the user changes the auto-sync interval in Settings.
	 */
	private synchronized void restartDaemon() {
		if (daemon != null && daemon.isAlive()) {
			daemon.interrupt();
			// Wait a brief moment so the old thread is truly gone before we
			// respawn; startDaemon() checks isAlive() so we must not race it.
			try {
				daemon.join(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		daemon = null;
		startDaemon();
	}

	/**
	 * Updates the auto-sync interval and immediately restarts the daemon so
	 * the new value takes effect right away (instead of waiting for the
	 * current sleep to expire).
	 */
	public void setIntervalMin(int minutes) {
		if (config == null) return;
		config.intervalMin = minutes;
		config.save();
		log("Auto-sync interval set to " + minutes + " min — restarting timer.");
		restartDaemon();
	}
}
