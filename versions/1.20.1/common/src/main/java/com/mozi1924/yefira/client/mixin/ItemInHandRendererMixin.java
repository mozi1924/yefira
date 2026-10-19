package com.mozi1924.yefira.client.mixin;

import com.mozi1924.yefira.client.ghost.GhostModeManager;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void onRenderHandsWithItems(float partialTicks, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffer, net.minecraft.client.player.LocalPlayer player, int combinedLight, CallbackInfo ci) {
        if (GhostModeManager.getInstance().isActive()) {
            ci.cancel();
        }
    }
}
