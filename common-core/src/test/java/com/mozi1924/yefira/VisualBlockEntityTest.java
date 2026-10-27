package com.mozi1924.yefira;

import com.mozi1924.yefira.compat.visual.VisualBlockEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class VisualBlockEntityTest {

    @Test
    public void testResolveSkullKind() {
        Assertions.assertEquals("player", VisualBlockEntity.resolveSkullKind("player_head"));
        Assertions.assertEquals("player", VisualBlockEntity.resolveSkullKind("player_wall_head"));
        Assertions.assertEquals("skeleton", VisualBlockEntity.resolveSkullKind("skeleton_skull"));
        Assertions.assertEquals("wither_skeleton", VisualBlockEntity.resolveSkullKind("wither_skeleton_skull"));
        Assertions.assertEquals("zombie", VisualBlockEntity.resolveSkullKind("zombie_head"));
        Assertions.assertEquals("creeper", VisualBlockEntity.resolveSkullKind("creeper_head"));
        Assertions.assertEquals("dragon", VisualBlockEntity.resolveSkullKind("dragon_head"));
        Assertions.assertEquals("piglin", VisualBlockEntity.resolveSkullKind("piglin_head"));
        Assertions.assertEquals("skeleton", VisualBlockEntity.resolveSkullKind("unknown_block"));
    }

    @Test
    public void testDecoratedPotSerialization() {
        String json = VisualBlockEntity.serializeDecoratedPot(
                "angler_pottery_sherd", "archer_pottery_sherd", "blade_pottery_sherd", "brewer_pottery_sherd"
        );
        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("\"id\":\"minecraft:decorated_pot\""));
        Assertions.assertTrue(json.contains("\"sherds\":[\"angler_pottery_sherd\",\"archer_pottery_sherd\",\"blade_pottery_sherd\",\"brewer_pottery_sherd\"]"));
    }

    @Test
    public void testPlayerSkullSerializationWithBase64Texture() {
        String skinUrl = "http://textures.minecraft.net/texture/abc123def456";
        String skinPayload = "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"}}}";
        String base64 = Base64.getEncoder().encodeToString(skinPayload.getBytes(StandardCharsets.UTF_8));

        String json = VisualBlockEntity.serializeSkull(
                "player",
                8,
                "Steve",
                "00000000-0000-0000-0000-000000000000",
                base64
        );

        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("\"id\":\"minecraft:skull\""));
        Assertions.assertTrue(json.contains("\"kind\":\"player\""));
        Assertions.assertTrue(json.contains("\"rotation\":8"));
        Assertions.assertTrue(json.contains("\"name\":\"Steve\""));
        Assertions.assertTrue(json.contains("\"skin_url\":\"" + skinUrl + "\""));
    }

    @Test
    public void testNonPlayerSkullSerialization() {
        String json = VisualBlockEntity.serializeSkull(
                "skeleton",
                4,
                null,
                null,
                null
        );

        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("\"id\":\"minecraft:skull\""));
        Assertions.assertTrue(json.contains("\"kind\":\"skeleton\""));
        Assertions.assertTrue(json.contains("\"rotation\":4"));
        Assertions.assertFalse(json.contains("\"profile\""));
    }
}
