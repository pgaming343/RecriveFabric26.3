package com.recrive.mixin;

import com.recrive.ui.RecriveScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adds a cloud-only (☁) square button to the title screen's icon row and keeps
 * the whole row centred on width/2 so two icons sit left of centre and two sit
 * right of it. Vanilla lays out three 20px icons centred (width/2-34, -10, +14).
 * A centred four-icon row is at width/2-46, -22, +2, +26, so the three vanilla
 * icons are shifted 12px left and the cloud button is placed to their right.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void recrive$addButton(CallbackInfo ci) {
		List<Button> icons = new ArrayList<>();
		for (var child : this.children()) {
			if (child instanceof Button b && b.getWidth() == 20 && b.getHeight() == 20
					&& b.getY() > this.height / 2) {
				icons.add(b);
			}
		}
		if (icons.isEmpty()) {
			return;
		}
		icons.sort(Comparator.comparingInt(Button::getX));

		// Shift the three vanilla icons 12px left so a four-icon row stays centred.
		for (Button b : icons) {
			b.setPosition(b.getX() - 12, b.getY());
		}

		Button last = icons.get(icons.size() - 1);
		int bx = last.getX() + last.getWidth() + 4; // 20px icon + 4px gap
		int by = last.getY();
		this.addRenderableWidget(Button.builder(Component.literal("☁"),
				b -> this.minecraft.setScreenAndShow(new RecriveScreen((TitleScreen) (Object) this)))
				.bounds(bx, by, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Recrive cloud sync — back up / restore worlds")))
				.build());
	}
}
