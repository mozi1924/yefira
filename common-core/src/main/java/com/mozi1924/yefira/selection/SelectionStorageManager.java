package com.mozi1924.yefira.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.compat.VersionCompat;
import com.mozi1924.yefira.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class SelectionStorageManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record SelectionData(BlockPos pos1, BlockPos pos2, ResourceKey<Level> dimension) {}

    public static Path getWorldStoragePath(Path worldRootDir) {
        return worldRootDir.resolve("yefira_selection.json");
    }

    public static Path getServerStoragePath(String serverIp) {
        String cleanIp = sanitizeFileName(serverIp);
        Path configDir = Services.PLATFORM.getConfigDirectory().resolve("yefira").resolve("servers");
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to create server config directory", e);
        }
        return configDir.resolve(cleanIp + ".json");
    }

    public static SelectionData loadFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!json.has("pos1") || !json.has("pos2")) {
                return null;
            }

            JsonObject p1 = json.getAsJsonObject("pos1");
            JsonObject p2 = json.getAsJsonObject("pos2");

            BlockPos pos1 = new BlockPos(p1.get("x").getAsInt(), p1.get("y").getAsInt(), p1.get("z").getAsInt());
            BlockPos pos2 = new BlockPos(p2.get("x").getAsInt(), p2.get("y").getAsInt(), p2.get("z").getAsInt());

            ResourceKey<Level> dimension = Level.OVERWORLD;
            if (json.has("dimension")) {
                String dimStr = json.get("dimension").getAsString();
                ResourceKey<Level> parsed = VersionCompat.level().parseDimensionKey(dimStr);
                if (parsed != null) {
                    dimension = parsed;
                }
            }

            return new SelectionData(pos1, pos2, dimension);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to load selection from path: {}", path, e);
            return null;
        }
    }

    public static void saveToPath(Path path, BlockPos pos1, BlockPos pos2, ResourceKey<Level> dimension) {
        if (path == null || pos1 == null || pos2 == null) {
            return;
        }

        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Path temp = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonObject json = new JsonObject();

            JsonObject p1 = new JsonObject();
            p1.addProperty("x", pos1.getX());
            p1.addProperty("y", pos1.getY());
            p1.addProperty("z", pos1.getZ());
            json.add("pos1", p1);

            JsonObject p2 = new JsonObject();
            p2.addProperty("x", pos2.getX());
            p2.addProperty("y", pos2.getY());
            p2.addProperty("z", pos2.getZ());
            json.add("pos2", p2);

            if (dimension != null) {
                json.addProperty("dimension", VersionCompat.level().getDimensionId(dimension));
            }

            byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            // Write to a sibling temp file and atomically rename it. A crash
            // mid-write must never truncate the previous selection to empty,
            // which would make the next load silently fall back to no selection.
            temp = Files.createTempFile(parent, "yefira-selection-", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;

            Yefira.LOGGER.info("Saved selection to {}", path);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to save selection to path: {}", path, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static void deleteStorageFile(Path path) {
        if (path != null && Files.exists(path)) {
            try {
                Files.delete(path);
                Yefira.LOGGER.info("Deleted selection file at {}", path);
            } catch (Exception e) {
                Yefira.LOGGER.error("Failed to delete selection file: {}", path, e);
            }
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "unknown_server";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
