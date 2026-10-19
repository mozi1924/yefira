package com.mozi1924.yefira.client.mixin;

import com.mozi1924.yefira.client.YefiraClient;
import com.mozi1924.yefira.client.ghost.GhostModeManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        if (!ghost.isActive()) {
            return;
        }

        // If a GUI screen (like PauseScreen, Chat, Inventory) is open, let vanilla handle it
        if (this.minecraft.screen != null) {
            return;
        }

        // 1. ESC key handling
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (ghost.isBoxCreating()) {
                if (action == GLFW.GLFW_PRESS) {
                    ghost.cancelBoxDrag();
                }
                ci.cancel();
                return;
            }
            // Allow ESC to open pause screen in normal ghost mode
            return;
        }

        // 2. Ghost Mode toggle key
        if (YefiraClient.keyGhostMode != null && YefiraClient.keyGhostMode.matches(key, scancode)) {
            if (action == GLFW.GLFW_PRESS) {
                ghost.toggle();
            }
            ci.cancel();
            return;
        }

        // 3. Focus on Selection: Numpad . or keyFocus
        if ((YefiraClient.keyFocus != null && YefiraClient.keyFocus.matches(key, scancode))
                || key == GLFW.GLFW_KEY_KP_DECIMAL) {
            if (action == GLFW.GLFW_PRESS) {
                ghost.focusSelection();
            }
            ci.cancel();
            return;
        }

        // 4. Open Settings / Control Screen: keyOpenGui
        if (YefiraClient.keyOpenGui != null && YefiraClient.keyOpenGui.matches(key, scancode)) {
            if (action == GLFW.GLFW_PRESS) {
                this.minecraft.setScreen(new com.mozi1924.yefira.client.gui.YefiraScreen());
            }
            ci.cancel();
            return;
        }

        // 5. Clear Selection: keyClear or DELETE
        if ((YefiraClient.keyClear != null && YefiraClient.keyClear.matches(key, scancode))
                || key == GLFW.GLFW_KEY_DELETE) {
            if (action == GLFW.GLFW_PRESS) {
                com.mozi1924.yefira.selection.SelectionManager.getInstance().clearSelection();
            }
            ci.cancel();
            return;
        }

        // 6. Create Preset Box (16x16x16): keyPresetBox
        if (YefiraClient.keyPresetBox != null && YefiraClient.keyPresetBox.matches(key, scancode)) {
            if (action == GLFW.GLFW_PRESS) {
                ghost.createPresetBoxAtCursorOrPivot(16);
            }
            ci.cancel();
            return;
        }

        // In Ghost Mode, block all vanilla gameplay key events (hotbar, drop item, chat, attack, use item)
        ci.cancel();
    }
}
