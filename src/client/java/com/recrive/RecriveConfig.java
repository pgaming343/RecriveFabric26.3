package com.recrive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persisted per-world toggles + Drive account + interval. Shared format with ~/sk-world-sync companion app. */
public final class RecriveConfig {
	/** Volatile: read by the daemon thread, written by the UI thread. */
	public volatile int intervalMin = 10;
	public volatile String remote = "";
	/** Upload speed profile: "balanced", "fast" (default) or "max". */
	public volatile String speedProfile = "fast";
	/**
	 * How many old local {@code <world>.bak-*} safety copies to keep.
	 * After every SUCCESSFUL sync the oldest extras are deleted (newest kept).
	 * 0 = delete all once the world is safely on Drive.
	 */
	public volatile int bakKeep = 1;
	public Map<String, Boolean> toggles = new HashMap<>();
	public Map<String, String> lastSync = new HashMap<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("recrive.json");
	}

	public static RecriveConfig load() {
		Path f = file();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f)) {
				RecriveConfig c = GSON.fromJson(r, RecriveConfig.class);
				if (c != null) {
					if (c.toggles == null) c.toggles = new HashMap<>();
					if (c.lastSync == null) c.lastSync = new HashMap<>();
					if (c.speedProfile == null || c.speedProfile.isBlank()) c.speedProfile = "fast";
					return c;
				}
			} catch (IOException | com.google.gson.JsonParseException e) {
				System.err.println("[recrive] config unreadable, using defaults: " + e.getMessage());
			}
		}
		return new RecriveConfig();
	}

	public synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			System.err.println("[recrive] could not save config: " + e.getMessage());
		}
	}
}
