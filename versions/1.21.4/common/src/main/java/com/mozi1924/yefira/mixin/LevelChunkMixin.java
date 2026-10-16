package com.mozi1924.yefira.mixin;

import com.mozi1924.yefira.encoder.BlockDataEncoder;
import com.mozi1924.yefira.network.WebSocketServerManager;
import com.mozi1924.yefira.selection.SelectionBox;
import com.mozi1924.yefira.selection.SelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow @Final private Level level;

    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN")
    )
    private void yefira$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState != null && oldState != state) {
            if (this.level != null && !this.level.isClientSide()) {
                SelectionManager selectionManager = SelectionManager.getInstance();
                if (selectionManager.hasSelection()) {
                    SelectionBox selection = selectionManager.getCurrentSelection();
                    if (selectionManager.getDimension() != null &&
                        this.level.dimension().equals(selectionManager.getDimension()) &&
                        selection.contains(pos)) {

                        BlockDataEncoder.BlockChangeEntry change = new BlockDataEncoder.BlockChangeEntry(pos.immutable(), state);
                        WebSocketServerManager.getInstance().queueDeltaUpdate(selection, change);
                    }
                }
            }
        }
    }
}
