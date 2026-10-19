package com.mozi1924.yefira.network;

import com.mozi1924.yefira.Yefira;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side anchor for the client's Ghost Mode.
 *
 * <p>Ghost Mode detaches the camera but leaves the player body in the world.
 * In survival that body would otherwise keep taking gravity, collision and
 * damage while the user is looking through a free camera. The client sends
 * {@code /yefira ghost on} when it enables Ghost Mode; this manager then pins
 * the body to its anchor, disables gravity and makes it invulnerable until the
 * matching {@code off}. The state is per-player and is restored on disable,
 * death, logout or server stop.</p>
 */
public final class GhostAnchorManager {

    private static final double MAX_ANCHOR_DRIFT_SQ = 0.05 * 0.05;

    private record Anchor(
            ServerLevel level,
            double x,
            double y,
            double z,
            boolean wasInvulnerable,
            boolean wasNoGravity
    ) {
    }

    private static final Map<UUID, Anchor> ANCHORS = new ConcurrentHashMap<>();

    private GhostAnchorManager() {
    }

    /** Starts (or refreshes) ghost protection for the player. */
    public static synchronized void enable(ServerPlayer player) {
        if (player == null || ANCHORS.containsKey(player.getUUID())) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Anchor anchor = new Anchor(
                level,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.isInvulnerable(),
                player.isNoGravity()
        );
        ANCHORS.put(player.getUUID(), anchor);
        applyProtection(player, anchor);
        Yefira.LOGGER.info("Ghost anchor enabled for {} at {} {} {}",
                player.getScoreboardName(), anchor.x(), anchor.y(), anchor.z());
    }

    /** Stops ghost protection and restores the player's previous flags. */
    public static synchronized boolean disable(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Anchor anchor = ANCHORS.remove(player.getUUID());
        if (anchor == null) {
            return false;
        }
        player.setInvulnerable(anchor.wasInvulnerable());
        player.setNoGravity(anchor.wasNoGravity());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        Yefira.LOGGER.info("Ghost anchor disabled for {}", player.getScoreboardName());
        return true;
    }

    public static boolean isActive(UUID playerUuid) {
        return playerUuid != null && ANCHORS.containsKey(playerUuid);
    }

    /** Pins every ghosting player back to their anchor once per server tick. */
    public static void tick(MinecraftServer server) {
        if (ANCHORS.isEmpty() || server == null) {
            return;
        }
        for (Map.Entry<UUID, Anchor> entry : ANCHORS.entrySet()) {
            UUID uuid = entry.getKey();
            Anchor anchor = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                ANCHORS.remove(uuid);
                continue;
            }
            if (player.isDeadOrDying() || player.level() != anchor.level()) {
                disable(player);
                continue;
            }
            applyProtection(player, anchor);
            double dx = player.getX() - anchor.x();
            double dy = player.getY() - anchor.y();
            double dz = player.getZ() - anchor.z();
            if (dx * dx + dy * dy + dz * dz > MAX_ANCHOR_DRIFT_SQ) {
                // 3-arg teleport exists in every supported era and keeps the
                // player in the same dimension.
                player.teleportTo(anchor.x(), anchor.y(), anchor.z());
            }
        }
    }

    /** Clears all anchors without restoring (used while the server is stopping). */
    public static void clearAll() {
        ANCHORS.clear();
    }

    private static void applyProtection(ServerPlayer player, Anchor anchor) {
        player.setInvulnerable(true);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setRemainingFireTicks(0);
        player.setAirSupply(player.getMaxAirSupply());
    }
}
