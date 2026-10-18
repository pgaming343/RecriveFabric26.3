# RECRIVE — AI handoff (everything used, to reproduce / improve)

## 1. Goal
Fabric mod "Recrive": cloud sync for Minecraft saves — per-world ON/OFF toggle,
auto backup, Drive sync of worlds + player data, lightweight, Fabric.
Companion GTK app exists too (`~/sk-world-sync`, same Drive layout).

## 2. Machine / paths (Linux, Ubuntu 26.04, no sudo)
- Saves (real): `~/.sklauncher/instances/fabric/saves/` → DG (1.6G), Testing (36M),
  `Ultimate Brewing Stations` (29M). Old copy: `~/.minecraft/saves/DG` (1.4G).
- Loader on instance: fabric-loader 0.19.5, MC 26.3 (SKLauncher).
- `~/bin/rclone` v1.75.2 (local install, in PATH). No remotes configured yet
  (`~/.config/rclone/rclone.conf` absent) — user must run `rclone config`.
- `~/gradle-dist/gradle-8.10.2` and `~/gradle-dist/gradle-9.8.0` (USE 9.8.0).
- `~/jdk25` (Temurin 25.0.4.1+1) — build JVM. Mod bytecode targets Java 21
  (`options.release = 21`). System JDKs: 8/17/21 under /usr/lib/jvm.
- Mod project: `~/recrive`. Built jar: `~/recrive/build/libs/recrive-1.0.0.jar` (27KB).
- Companion: `~/sk-world-sync/` (world_sync.py GTK3/PyGObject, sk-sync bash CLI,
  run.sh, launch-with-sk.sh, README-SETUP.md). Config:
  `~/.config/sk-world-sync/config.json`. Drive folder: `MC-World-Sync/<world>`.

## 3. Mod toolchain (exact, verified)
- gradle.properties: minecraft_version=1.21.11, yarn_mappings=1.21.11+build.6,
  loader_version=0.19.5, fabric_version=0.141.6+1.21.11, loom_version=1.19.1.
  (1.21.11 chosen because yarn/intermediary for 26.3 are NOT on Maven yet;
  fabric-api DOES publish +26.3 builds, e.g. 0.163.0+26.3.)
- build.gradle: plugins `fabric-loom:${loom_version}` + `java`; deps:
  `com.mojang:minecraft:1.21.11`, `net.fabricmc:yarn:1.21.11+build.6:v2`,
  `net.fabricmc:fabric-loader:0.19.5`,
  `net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11` (note GROUP is
  net.fabricmc.fabric-api, not net.fabricmc). Single source set — DO NOT use
  `loom { splitEnvironmentSourceSets() }` or net.minecraft.client won't resolve
  from src/main/java. resources expand version into fabric.mod.json.
- fabric.mod.json: id recrive 1.0.0, env client, client entry
  com.recrive.RecriveMod, mixins recrive.mixins.json, depends loader>=0.16,
  fabric-api *, minecraft ~1.21.11, java >=21. NO icon line.
- recrive.mixins.json: package com.recrive.mixin, JAVA_21, client:
  TitleScreenMixin, GameMenuScreenMixin. No refmap needed — Loom ≥1.13 remaps
  mixin targets directly (verified: jar classes reference class_437 etc.).
- Build cmd: `cd ~/recrive && JAVA_HOME=~/jdk25 ~/gradle-dist/gradle-9.8.0/bin/gradle build`
  (org.gradle.daemon=false, jvmargs -Xmx3G in gradle.properties).

## 4. Java sources (Yarn 1.21.11 APIs — all verified compiling)
- `com.recrive.RecriveMod implements ClientModInitializer`: loads RecriveConfig,
  inits WorldSyncManager, registers KeyBinding currently
  `new KeyBinding("key.recrive.open", Type.KEYSYM, GLFW_KEY_UNKNOWN, Category.MISC)`
  (unbound; String category does NOT compile — must be enum), END_CLIENT_TICK
  opens screen, `/recrive` via ClientCommandRegistrationCallback.
- `com.recrive.RecriveConfig`: Gson JSON at FabricLoader configDir/recrive.json;
  fields intervalMin=10, remote="", toggles{}, lastSync{}.
- `com.recrive.sync.RcloneBackend`: finds ~/bin/rclone else PATH; listRemotes();
  upload= `rclone copy <dir> <remote>:MC-World-Sync/<world> --transfers 4
  --checkers 8 --fast-list --update --exclude session.lock --drive-chunk-size 32M`;
  download analogous; backupLocal() timestamped copy; dirSize/humanSize.
- `com.recrive.sync.WorldSyncManager`: savesDir()=runDirectory/saves;
  scanWorlds()=subfolders with level.dat(_old); daemon thread sleeps
  intervalMin, uploads toggled worlds; openWorldName() via
  `client.getServer()!=null (& getSaveProperties().getLevelName())` —
  NOTE: IntegratedServer has NO isSinglePlayer() method; skip upload/restore of
  open world; syncNow()/restore() on own threads; status + log listeners.
- `com.recrive.ui.RecriveScreen extends Screen`: account cycler button, interval
  cycler, 5 rows/page (toggle ButtonWidget ON/OFF + Sync now + Restore),
  Prev/Next, Done; re-render via `client.setScreen(new RecriveScreen(parent,page))`
  — NOTE: Screen.init(MinecraftClient,int,int) does NOT exist in 1.21.11, never
  call init() directly. Tooltips via Tooltip.of. last-sync line in render().
- Mixins extend Screen w/ `protected Mixin(Text)` ctor; @Inject init@RETURN;
  TitleScreen button top-right (width-212,8,200x20), GameMenuScreen top-right
  (width-152,8,140x20); open via client.setScreen(new RecriveScreen(this)).
- `assets/recrive/lang/en_us.json`: key.recrive.open, category.recrive.
- MIT LICENSE file (referenced by jar task).

## 5. Bugs hit + fixes (do not regress)
1. loom 1.19.1 + Gradle 8.10 → needs Gradle ≥9.8 → use gradle-9.8.0.
2. loom 1.19.1 needs JVM 25+ → JAVA_HOME=~/jdk25.
3. loom 1.8.13 on JVM21+G8 → "Unsupported unpick version" for 1.21.11 → use 1.19.1.
4. fabric-api group must be net.fabricmc.fabric-api.
5. splitEnvironmentSourceSets() hides client classes → removed.
6. KeyBinding category: enum Category.MISC, not String.
7. No IntegratedServer.isSinglePlayer(); use getServer()!=null.
8. No Screen.init(client,w,h); recreate screen instead.
9. No refmap in jar — EXPECTED, modern Loom remaps directly.

## 6. Suggested improvements (for the next AI)
- Retarget to MC 26.3 when yarn 26.3 + intermediary publish (4-line change).
- In-game test on 1.21.11 instance; add run configs.
- Progress bar for uploads (parse rclone --progress / --use-json-log).
- Conflict handling if Drive copy newer (rclone check/bisync, timestamps).
- Also sync options.txt/servers.dat (player settings across devices).
- ModMenu integration, Cloth Config screen, disconnect-button placement review
  for GameMenuScreen on 26.3 layout.
- Direct Google Drive API backend as alternative to rclone dependency.
- Unit tests for scan/toggle/config; `gradle check` currently has none.
