package com.mozi1924.yefira.client.mixin;

import com.mozi1924.yefira.client.ghost.GhostModeManager;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class ClientInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(boolean slowDown, float movementMultiplier, CallbackInfo ci) {
        if (GhostModeManager.getInstance().isActive()) {
            KeyboardInput input = (KeyboardInput) (Object) this;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
            input.forwardImpulse = 0.0f;
            input.leftImpulse = 0.0f;
        }
    }
}
