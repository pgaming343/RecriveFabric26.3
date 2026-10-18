package com.recrive.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Thin wrapper over the {@code rclone} binary for Google Drive sync.
 * Multi-account: every {@code rclone} remote (added via {@code rclone config}
 * with browser OAuth) is a selectable Google account. Same remote folder
 * layout as the sk-world-sync companion app so both interoperate:
 * {@code <remote>:MC-World-Sync/<world>}.
 */
public final class RcloneBackend {
	public static final String REMOTE_FOLDER = "MC-World-Sync";
	private static final long OP_TIMEOUT_MIN = 60;
	/** Most recent localhost OAuth URL (shown in-game as a manual fallback link). */
	private static volatile String lastAuthUrl = "";
	/**
	 * Auth URLs already handed to the browser this session. Guarantees a given
	 * OAuth link is opened exactly once, so a retry/duplicate line can never
	 * produce a second browser tab (two sign-ins).
	 */
	private static final java.util.Set<String> openedAuthUrls =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	/**
	 * The {@code rclone authorize} process currently waiting for Google sign-in.
	 * Tracked so we can destroy it when the game closes or a new attempt starts —
	 * otherwise it survives as an orphan, keeps the auth port (53682) busy, and
	 * the next launch can't print a URL or open the browser.
	 */
	private static volatile Process activeAuthorize;

	static {
		// Last-resort cleanup: kill any in-flight authorize when the JVM exits.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> destroyActiveAuthorize(),
				"recrive-auth-cleanup"));
	}

	public static String lastAuthUrl() {
		return lastAuthUrl;
	}

	/** Clears the stored auth URL so a new attempt never shows a stale link. */
	public static void resetAuthUrl() {
		lastAuthUrl = "";
	}

	/** Force-kills the currently tracked authorize process (if any). */
	private static void destroyActiveAuthorize() {
		Process p = activeAuthorize;
		activeAuthorize = null;
		if (p != null) {
			try {
				p.destroyForcibly();
			} catch (Exception ignored) {
			}
		}
	}

	private RcloneBackend() {
	}

	public static String binary() {
		String home = System.getProperty("user.home");
		Path local = Path.of(home, "bin", "rclone");
		if (Files.isExecutable(local)) return local.toString();
		for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
			Path cand = Path.of(dir, "rclone");
			if (Files.isExecutable(cand)) return cand.toString();
			Path win = Path.of(dir, "rclone.exe");
			if (Files.isExecutable(win)) return win.toString();
		}
		return "rclone";
	}

	public static boolean available() {
		try {
			Process p = new ProcessBuilder(binary(), "version").redirectErrorStream(true).start();
			boolean done = p.waitFor(15, TimeUnit.SECONDS);
			return done && p.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			// NOTE: do NOT re-interrupt here — this runs on the main thread during
			// mod init; a set interrupt flag makes Fabric's later NIO jar reads
			// fail with ClosedByInterruptException and crash the game.
			return false;
		}
	}

	public static List<String> listRemotes() {
		List<String> out = new ArrayList<>();
		try {
			Process p = new ProcessBuilder(binary(), "listremotes").redirectErrorStream(false).start();
			String stdout = new String(p.getInputStream().readAllBytes());
			p.waitFor(20, TimeUnit.SECONDS);
			for (String line : stdout.split("\\R")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				// rclone outputs remotes as "name:" — strip trailing colon for clean storage
				if (line.endsWith(":")) line = line.substring(0, line.length() - 1);
				out.add(line);
			}
		} catch (IOException e) {
			// rclone missing — not an interrupt; never set the flag here.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return out;
	}

	/** Returns true when at least one rclone remote is configured. */
	public static boolean hasAnyRemote() {
		return !listRemotes().isEmpty();
	}

	/**
	 * Picks the next default remote name ("gdrive", "gdrive2", "gdrive3", …)
	 * that doesn't clash with already-existing remotes.
	 */
	public static String nextRemoteName() {
		List<String> existing = listRemotes();
		String base = "gdrive";
		if (!existing.contains(base)) return base;
		for (int i = 2; ; i++) {
			String cand = base + i;
			if (!existing.contains(cand)) return cand;
		}
	}

	/**
	 * Creates a new rclone Drive remote without relying on rclone's own
	 * browser handling (which fails when launched from inside the game):
	 * runs {@code rclone authorize drive --auth-no-open-browser}, opens the
	 * printed localhost auth URL in the browser ourselves, captures the token
	 * from stdout, then {@code rclone config create <name> drive token <token>}.
	 *
	 * <p>This is a <b>blocking</b> call — run it on a background thread.
	 * The authorize step waits for the user to complete browser OAuth (up to 5 minutes).
	 */
	public static Result createRemote(String name) {
		// Every attempt starts clean: drop any stale link and kill orphaned
		// authorize processes (a closed game leaves one holding the auth port,
		// which is why a restart used to jump straight to "waiting").
		resetAuthUrl();
		killStaleAuthorize();
		try {
			// Step 1: start authorize; it prints a localhost URL for the user to visit.
			Process auth = new ProcessBuilder(binary(), "authorize", "drive", "--auth-no-open-browser")
					.redirectErrorStream(true).start();
			activeAuthorize = auth;

			StringBuilder authOutput = new StringBuilder();
			String token = null;
			boolean browserOpened = false;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(auth.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					authOutput.append(line).append("\n");
					System.out.println("[recrive][authorize] " + line);
					if (!browserOpened) {
						String url = extractAuthUrl(line);
						if (url != null) {
							browserOpened = true;
							lastAuthUrl = url;
							openBrowser(url);
						}
					}
					if (token == null) {
						String t = extractToken(line + "\n");
						if (t != null) token = t;
					}
				}
			}

			boolean done = auth.waitFor(5, TimeUnit.MINUTES);
			if (!done) {
				auth.destroyForcibly();
				return Result.fail("Browser authorization timed out (5 min). Try again.");
			}
			// Bind conflicts (leftover authorize holding the port) → retry once clean.
			// But if a browser tab was already opened, do NOT retry — that would
			// start a second sign-in. Ask the user to try again instead.
			if (auth.exitValue() != 0
					&& authOutput.toString().contains("address already in use")) {
				if (browserOpened) {
					return Result.fail("Auth port busy — close any open sign-in tabs, "
							+ "wait a few seconds, then click Connect again.");
				}
				killStaleAuthorize();
				return createRemoteRetry(name);
			}
			if (auth.exitValue() != 0) {
				return Result.fail(friendlyAuthorizeError(authOutput.toString(), auth.exitValue()));
			}
			if (token == null) token = extractToken(authOutput.toString());
			if (token == null) {
				return Result.fail("Could not extract OAuth token from rclone output. "
						+ "Try running `rclone config` manually in a terminal.");
			}

			// Step 2: Create the remote non-interactively.
			Process create = new ProcessBuilder(
					binary(), "config", "create", name, "drive",
					"token", token
			).redirectErrorStream(true).start();
			String createOut = new String(create.getInputStream().readAllBytes());
			create.waitFor(30, TimeUnit.SECONDS);

			if (create.exitValue() != 0) {
				return Result.fail("rclone config create failed: " + tail(createOut, 300));
			}
			return Result.ok(name);

		} catch (IOException e) {
			return Result.fail("Error during authorization: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.fail("Error during authorization: " + e.getMessage());
		}
	}

	/** Single clean retry after killing stale authorize processes (no infinite loop). */
	private static Result createRemoteRetry(String name) {
		resetAuthUrl();
		try {
			Process auth = new ProcessBuilder(binary(), "authorize", "drive", "--auth-no-open-browser")
					.redirectErrorStream(true).start();
			activeAuthorize = auth;
			StringBuilder out = new StringBuilder();
			String token = null;
			boolean opened = false;
			try (BufferedReader r = new BufferedReader(new InputStreamReader(auth.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					out.append(line).append("\n");
					System.out.println("[recrive][authorize] " + line);
					if (!opened) {
						String url = extractAuthUrl(line);
						if (url != null) {
							opened = true;
							lastAuthUrl = url;
							openBrowser(url);
						}
					}
					if (token == null) token = extractToken(line + "\n");
				}
			}
			if (!auth.waitFor(5, TimeUnit.MINUTES)) {
				auth.destroyForcibly();
				return Result.fail("Browser authorization timed out (5 min). Try again.");
			}
			if (auth.exitValue() != 0) {
				return Result.fail(friendlyAuthorizeError(out.toString(), auth.exitValue()));
			}
			if (token == null) token = extractToken(out.toString());
			if (token == null) return Result.fail("Could not extract OAuth token. Try `rclone config` in a terminal.");
			Process create = new ProcessBuilder(binary(), "config", "create", name, "drive", "token", token)
					.redirectErrorStream(true).start();
			String createOut = new String(create.getInputStream().readAllBytes());
			create.waitFor(30, TimeUnit.SECONDS);
			if (create.exitValue() != 0) return Result.fail("rclone config create failed: " + tail(createOut, 300));
			return Result.ok(name);
		} catch (IOException e) {
			return Result.fail("Error during authorization: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.fail("Error during authorization: " + e.getMessage());
		}
	}


	/**
	 * Maps rclone's raw failure into an actionable message. The most common
	 * case ("No code returned by remote server") means the browser reached the
	 * localhost callback WITHOUT the Google code — typically an adblocker /
	 * Brave Shields breaking the redirect, or signing in via a separate tab
	 * instead of approving the consent screen, or reloading the localhost page.
	 */
	private static String friendlyAuthorizeError(String output, int exit) {
		if (output.contains("No code returned by remote server")) {
			return "Google didn't send back a login code. In the opened tab: turn OFF adblock / Brave Shields "
					+ "for the page, approve the Google consent screen there (don't sign in via a separate tab, "
					+ "don't reload the localhost page), then click + Add again. "
					+ "Alternative: run `~/bin/rclone config` in a terminal.";
		}
		if (output.contains("address already in use")) {
			return "Auth port busy (leftover login attempt). Wait 10 s and click + Add again.";
		}
		return "rclone authorize failed (exit " + exit + "): " + tail(output, 300);
	}

	/**
	 * Kills leftover {@code rclone authorize} processes that would steal the auth
	 * port (53682). Only processes whose command line contains "authorize" are
	 * touched, so a running sync is never harmed. Cross-platform: {@code pkill}
	 * on Linux/macOS, PowerShell on Windows. Safe to call when none exist.
	 */
	private static void killStaleAuthorize() {
		destroyActiveAuthorize();
		String os = System.getProperty("os.name", "").toLowerCase();
		ProcessBuilder pb;
		try {
			if (os.contains("win")) {
				pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
						"Get-CimInstance Win32_Process -Filter \"name='rclone.exe'\" | "
								+ "Where-Object { $_.CommandLine -like '*authorize*' } | "
								+ "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }");
			} else {
				pb = new ProcessBuilder("pkill", "-f", "rclone authorize");
			}
			pb.redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
		} catch (IOException e) {
			// killer tool not available — nothing we can do.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// Give the OS a moment to release the auth port before the next bind.
		try {
			Thread.sleep(1500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Finds the localhost LOGIN URL rclone prints (http://127.0.0.1:PORT/auth?state=...).
	 * Must require "/auth?": newer rclone first prints a notice containing the bare
	 * redirect URL "http://127.0.0.1:53682/" — opening that hits the callback with
	 * no code and rclone instantly fails with "No code returned by remote server".
	 */
	private static String extractAuthUrl(String line) {
		Matcher m = Pattern.compile("(https?://127\\.0\\.0\\.1:\\d+/auth\\?\\S+)").matcher(line);
		if (m.find()) return m.group(1).replaceAll("[\"'<>)]+$", "");
		return null;
	}

	/**
	 * Opens a URL in the user's default browser. Tries AWT Desktop first
	 * (works on most Windows/macOS/Linux desktops), then falls back to the
	 * platform's own launcher: {@code rundll32}/{@code start} on Windows,
	 * {@code open} on macOS, {@code xdg-open} on Linux/BSD. If everything
	 * fails the URL is printed (and shown in-game) so the user can paste it.
	 */
	private static void openBrowser(String url) {
		System.out.println("[recrive] Opening browser for Google sign-in: " + url);
		// Never open the same OAuth link twice — a duplicate would show a second
		// sign-in page. Different attempts use a new state, so they still open.
		if (!openedAuthUrls.add(url)) {
			System.out.println("[recrive] (already opened this link — skipping duplicate)");
			return;
		}
		try {
			if (java.awt.Desktop.isDesktopSupported()
					&& java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
				java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
				return;
			}
		} catch (Throwable e) {
			System.out.println("[recrive] Desktop.browse failed: " + e.getMessage());
		}

		String os = System.getProperty("os.name", "").toLowerCase();
		String[][] candidates;
		if (os.contains("win")) {
			candidates = new String[][]{
					{"rundll32", "url.dll,FileProtocolHandler", url},
					{"cmd", "/c", "start", "", url}
			};
		} else if (os.contains("mac")) {
			candidates = new String[][]{{"open", url}};
		} else {
			candidates = new String[][]{{"xdg-open", url}};
		}
		for (String[] cmd : candidates) {
			try {
				new ProcessBuilder(cmd).redirectErrorStream(true).start();
				return;
			} catch (IOException e) {
				System.out.println("[recrive] " + cmd[0] + " failed: " + e.getMessage());
			}
		}
		System.out.println("[recrive] Could not open a browser automatically. "
				+ "Please visit this URL manually: " + url);
	}

	/** Extracts the JSON token blob from rclone authorize output. */
	private static String extractToken(String output) {
		// Look for a JSON object containing "access_token"
		Pattern p = Pattern.compile("\\{[^}]*\"access_token\"[^}]*\\}", Pattern.DOTALL);
		Matcher m = p.matcher(output);
		if (m.find()) return m.group();
		// Fallback: look between the paste markers
		int start = output.indexOf("--->\n");
		int end = output.indexOf("\n<---");
		if (start >= 0 && end > start) {
			String candidate = output.substring(start + 5, end).trim();
			if (candidate.startsWith("{")) return candidate;
		}
		return null;
	}

	/** {@code <remote>:MC-World-Sync/<key>.zip} — the single-file backup. */
	public static String remoteZipPath(String remote, String key) {
		return normalized(remote) + REMOTE_FOLDER + "/" + key + ".zip";
	}

	/**
	 * Packs a world into a single uncompressed-by-CPU .zip (region files are
	 * already compressed, so NO_COMPRESSION just frames them — fastest possible
	 * packing, ~NVMe speed). Skips session.lock. One big file then uploads at
	 * full line rate with zero per-file API overhead: the .zip path for the
	 * first backup of a 1–2 GB world.
	 */
	public static Path zipWorld(Path worldDir) throws IOException {
		Path tmp = Files.createTempFile(worldDir.getFileName() + "-", ".zip");
		try (OutputStream fos = Files.newOutputStream(tmp);
				BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 20);
				ZipOutputStream zos = new ZipOutputStream(bos)) {
			zos.setLevel(Deflater.NO_COMPRESSION);
			List<Path> files;
			try (Stream<Path> walk = Files.walk(worldDir)) {
				files = walk.filter(Files::isRegularFile).sorted().toList();
			}
			for (Path p : files) {
				String name = worldDir.relativize(p).toString().replace('\\', '/');
				if (name.equals("session.lock")) continue;
				ZipEntry e = new ZipEntry(name);
				e.setTime(Files.getLastModifiedTime(p).toMillis());
				zos.putNextEntry(e);
				Files.copy(p, zos);
				zos.closeEntry();
			}
		} catch (IOException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
			}
			throw e;
		}
		return tmp;
	}

	/** Unpacks a .zip backup over {@code destDir} (cleared first by caller). */
	public static void unzipWorld(Path zip, Path destDir) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(
				new BufferedInputStream(Files.newInputStream(zip), 1 << 20))) {
			ZipEntry e;
			while ((e = zis.getNextEntry()) != null) {
				Path out = destDir.resolve(e.getName()).normalize();
				if (!out.startsWith(destDir)) throw new IOException("zip entry escapes target: " + e.getName());
				if (e.isDirectory()) {
					Files.createDirectories(out);
				} else {
					if (out.getParent() != null) Files.createDirectories(out.getParent());
					Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
				}
				zis.closeEntry();
			}
		}
	}
	/** Deletes everything inside a dir (for a clean .zip restore). */
	public static void clearDir(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (!p.equals(dir)) Files.deleteIfExists(p);
			}
		}
	}

	/**
	 * Retention: deletes old local {@code <world>.bak-*} safety copies in the
	 * saves folder, keeping the newest {@code keep} (0 = delete all).
	 * Called after every SUCCESSFUL sync — at that point the world is verified
	 * on Drive, so older local safety copies are redundant. Never touches the
	 * live world folder itself (prefix match includes the trailing ".bak-").
	 *
	 * @return names of deleted backups (for logging)
	 */
	public static List<String> pruneBackups(Path savesDir, String worldName, int keep) {
		List<String> deleted = new ArrayList<>();
		if (!Files.isDirectory(savesDir)) return deleted;
		String prefix = worldName + ".bak-";
		List<Path> baks;
		try (Stream<Path> list = Files.list(savesDir)) {
			baks = list.filter(p -> Files.isDirectory(p)
					&& p.getFileName().toString().startsWith(prefix))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			return deleted;
		}
		int toDelete = Math.max(0, baks.size() - Math.max(0, keep));
		for (int i = 0; i < toDelete; i++) {
			Path bak = baks.get(i);
			try (Stream<Path> walk = Files.walk(bak)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(p);
				}
				deleted.add(bak.getFileName().toString());
			} catch (IOException ignored) {
				// Leave partial dirs alone; next successful sync retries.
			}
		}
		return deleted;
	}

	/** Uploads one .zip with a fat chunk size — single stream at line rate. */
	public static Result uploadZip(Path zipFile, String remote, String key, SyncProgress progress) {
		String dest = remoteZipPath(remote, key);
		return runWithProgress(progress, "copyto", zipFile.toString(), dest,
				"--transfers", "1", "--drive-chunk-size", "128M",
				"--contimeout", "60s", "--timeout", "10m",
				"--retries", "1", "--low-level-retries", "3");
	}

	public static Result downloadZip(String remote, String key, Path destZip, SyncProgress progress) {
		String src = remoteZipPath(remote, key);
		return runWithProgress(progress, "copyto", src, destZip.toString(),
				"--transfers", "1", "--drive-chunk-size", "128M",
				"--contimeout", "60s", "--timeout", "10m",
				"--retries", "1", "--low-level-retries", "3");
	}

	/** Deletes a connected Google account (rclone remote). Non-interactive. */
	public static Result deleteRemote(String name) {
		if (name == null || name.isBlank()) return Result.fail("no account selected");
		String clean = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
		return run("config", "delete", clean);
	}

	/**
	 * Proves the .zip landed on Drive: {@code rclone lsl} reports the remote
	 * file size, which must equal the local .zip size.
	 */
	public static Result verifyZip(Path localZip, String remote, String key) {
		long local;
		try {
			local = Files.size(localZip);
		} catch (IOException e) {
			return Result.fail("local .zip gone: " + e.getMessage());
		}
		Result lsl = run("lsl", remoteZipPath(remote, key));
		if (!lsl.ok()) return Result.fail("not found on Drive: " + tail(lsl.detail(), 200));
		Matcher m = Pattern.compile("^\\s*(\\d+)\\s", Pattern.MULTILINE).matcher(lsl.detail());
		if (m.find() && Long.parseLong(m.group(1)) == local) {
			return Result.ok("1 file, " + humanSize(local) + " on Drive");
		}
		return Result.fail("size mismatch on Drive");
	}

	private static String normalized(String remote) {
		if (remote == null || remote.isBlank()) return "";
		return remote.endsWith(":") ? remote : remote + ":";
	}

	/**
	 * Matches rclone's one-line stats, e.g.
	 * {@code 12.500 MiB / 100.000 MiB, 12%, 2.500 MiB/s, ETA 35s}
	 */
	private static final Pattern STATS = Pattern.compile(
			"([0-9.]+ ?[KMGTP]?i?B) / ([0-9.]+ ?[KMGTP]?i?B), (-|\\d+%), ([0-9.]+ ?[KMGTP]?i?B/s), ETA (\\S+)");

	private static final long STALL_TIMEOUT_MIN = 10;

	private static Result runWithProgress(SyncProgress progress, String... args) {
		if (progress == null) return run(args);
		List<String> cmd = new ArrayList<>();
		cmd.add(binary());
		cmd.addAll(List.of(args));
		cmd.addAll(List.of("--stats", "1s", "--stats-one-line", "--stats-log-level", "NOTICE"));
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			progress.setCanceller(p::destroyForcibly);
			java.util.concurrent.atomic.AtomicLong lastOutput = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
			java.util.concurrent.atomic.AtomicBoolean stalled = new java.util.concurrent.atomic.AtomicBoolean(false);
			// Watchdog: rclone sometimes stops emitting output while finalizing
			// (looks "stuck at 100%"). Kill it after 10 silent minutes instead
			// of hanging forever; the retry/timeout flags usually finish first.
			Thread watch = new Thread(() -> {
				try {
					while (p.isAlive()) {
						Thread.sleep(30_000L);
						if (System.currentTimeMillis() - lastOutput.get() > STALL_TIMEOUT_MIN * 60_000L && p.isAlive()) {
							stalled.set(true);
							p.destroyForcibly();
							return;
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}, "recrive-watchdog");
			watch.setDaemon(true);
			watch.start();
			StringBuilder log = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					lastOutput.set(System.currentTimeMillis());
					Matcher m = STATS.matcher(line);
					if (m.find()) {
						String pct = m.group(3);
						int percent = pct.equals("-") ? progress.percent() : Integer.parseInt(pct.replace("%", ""));
						progress.stats(percent, m.group(1), m.group(2), m.group(4), m.group(5));
					} else {
						log.append(line).append('\n');
						if (log.length() > 16000) log.delete(0, log.length() - 8000);
					}
				}
			} finally {
				progress.setCanceller(null);
			}
			boolean done = p.waitFor(OP_TIMEOUT_MIN, TimeUnit.MINUTES);
			if (!done) {
				p.destroyForcibly();
				return Result.fail("timed out after " + OP_TIMEOUT_MIN + " min");
			}
			if (progress.isCancelled()) return Result.fail("cancelled by user");
			if (stalled.get()) {
				return Result.fail("stalled — no progress for " + STALL_TIMEOUT_MIN
						+ " min. Check your connection and retry; last Drive message: " + tail(log.toString(), 300));
			}
			if (p.exitValue() == 0) return Result.ok(tail(log.toString(), 300));
			return Result.fail("rclone exited " + p.exitValue() + ": " + tail(log.toString(), 2000));
		} catch (IOException e) {
			return Result.fail(e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.fail(e.getMessage());
		}
	}

	private static Result run(String... args) {
		List<String> cmd = new ArrayList<>();
		cmd.add(binary());
		cmd.addAll(List.of(args));
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String log = new String(p.getInputStream().readAllBytes());
			boolean done = p.waitFor(OP_TIMEOUT_MIN, TimeUnit.MINUTES);
			if (!done) {
				p.destroyForcibly();
				return Result.fail("timed out after " + OP_TIMEOUT_MIN + " min");
			}
			if (p.exitValue() == 0) return Result.ok(tail(log, 300));
			return Result.fail("rclone exited " + p.exitValue() + ": " + tail(log, 500));
		} catch (IOException e) {
			return Result.fail(e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.fail(e.getMessage());
		}
	}

	private static String tail(String s, int n) {
		if (s == null) return "";
		return s.length() <= n ? s : "…" + s.substring(s.length() - n);
	}

	/** Timestamped local copy of a world folder, used before restore. */
	public static Path backupLocal(Path worldDir) throws IOException {
		Path bak = worldDir.resolveSibling(
				worldDir.getFileName() + ".bak-" + java.time.LocalDateTime.now()
						.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		try (Stream<Path> walk = Files.walk(worldDir)) {
			for (Path src : (Iterable<Path>) walk::iterator) {
				Path dst = bak.resolve(worldDir.relativize(src));
				if (Files.isDirectory(src)) Files.createDirectories(dst);
				else {
					Files.createDirectories(dst.getParent());
					Files.copy(src, dst);
				}
			}
		}
		return bak;
	}

	public static long dirSize(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		} catch (IOException e) {
			return 0L;
		}
	}

	public static String humanSize(long bytes) {
		if (bytes >= 1L << 30) return String.format("%.2f GB", bytes / (double) (1L << 30));
		if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
		return (bytes >> 10) + " KB";
	}

	public record Result(boolean ok, String detail) {
		public static Result ok(String d) {
			return new Result(true, d);
		}

		public static Result fail(String d) {
			return new Result(false, d);
		}
	}
}
