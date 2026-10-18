package com.recrive;

import com.mojang.blaze3d.platform.InputConstants;
import com.recrive.sync.RcloneBackend;
import com.recrive.sync.WorldSyncManager;
import com.recrive.ui.RecriveScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Recrive — cloud sync for Minecraft saves (ZIP-only).
 * Entry: slim "☁ Sync" buttons (title + pause), hotkey (unbound by default).
 */
public class RecriveMod implements ClientModInitializer {
	public static final String MOD_ID = "recrive";
	public static RecriveConfig config;
	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		config = RecriveConfig.load();
		WorldSyncManager.get().init(config);

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.recrive.open", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				// 26.3 exposes no current-screen getter, so open unconditionally;
				// closing with a null parent returns straight to the game.
				client.setScreenAndShow(new RecriveScreen(null));
			}
		});

		// NOTE: progress cards are drawn by GameMenuScreenMixin at the end of the
		// pause screen's render (screen layer = crisp). Nothing is drawn in the
		// HUD layer, because the pause blur would smear it — and nothing is
		// drawn over live gameplay.

		if (!RcloneBackend.available()) {
			System.out.println("[recrive] rclone not found — install it and run `rclone config` (see README). Sync disabled until then.");
		} else {
			System.out.println("[recrive] ready. saves: " + WorldSyncManager.get().savesDir());
		}
	}
}
