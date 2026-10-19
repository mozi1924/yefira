package com.mozi1924.yefira.client.mixin;

import com.mozi1924.yefira.client.ghost.GhostModeManager;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow private boolean initialized;
    @Shadow private BlockGetter level;
    @Shadow private Entity entity;
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true)
    private void onSetup(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTickTime, CallbackInfo ci) {
        GhostModeManager ghost = GhostModeManager.getInstance();
        if (ghost.isActive()) {
            this.initialized = true;
            this.level = level;
            this.entity = entity;
            this.detached = true;

            Vec3 ghostPos = ghost.getCameraPos();
            this.setPosition(ghostPos);
            this.setRotation(ghost.getYaw(), ghost.getPitch());

            ci.cancel();
        }
    }
}
