package com.mozi1924.yefira;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mozi1924.yefira.selection.SelectionStorageManager;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class SelectionStorageTest {

    @Test
    public void testSaveReplacesAtomically() throws Exception {
        Path dir = Files.createTempDirectory("yefira-selection-test");
        Path file = dir.resolve("yefira_selection.json");
        try {
            SelectionStorageManager.saveToPath(
                    file, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), null);
            JsonObject first = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Assertions.assertEquals(1, first.getAsJsonObject("pos1").get("x").getAsInt());
            Assertions.assertEquals(4, first.getAsJsonObject("pos2").get("x").getAsInt());

            // Overwriting must replace the previous document without leaving a
            // partially-written temp file behind.
            SelectionStorageManager.saveToPath(
                    file, new BlockPos(10, 20, 30), new BlockPos(40, 50, 60), null);
            JsonObject second = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Assertions.assertEquals(10, second.getAsJsonObject("pos1").get("x").getAsInt());
            Assertions.assertEquals(40, second.getAsJsonObject("pos2").get("x").getAsInt());

            try (Stream<Path> entries = Files.list(dir)) {
                Assertions.assertFalse(
                        entries.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                        "atomic save must clean up its temporary file");
            }
        } finally {
            try (Stream<Path> entries = Files.walk(dir)) {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
}
