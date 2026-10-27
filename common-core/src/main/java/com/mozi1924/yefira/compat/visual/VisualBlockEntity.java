package com.mozi1924.yefira.compat.visual;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Universal Single Source of Truth (SSOT) serializer for Visual Semantic NBT JSON.
 * Pure Java implementation decoupled from Minecraft and Authlib compilation discrepancies.
 */
public final class VisualBlockEntity {
    private VisualBlockEntity() {}

    /**
     * Resolves the canonical skull kind string from a block registry path.
     * E.g. "player_head" -> "player", "skeleton_wall_skull" -> "skeleton".
     */
    public static String resolveSkullKind(String blockPath) {
        if (blockPath == null) return "skeleton";
        if (blockPath.contains("wither_skeleton")) return "wither_skeleton";
        if (blockPath.contains("skeleton")) return "skeleton";
        if (blockPath.contains("zombie")) return "zombie";
        if (blockPath.contains("player")) return "player";
        if (blockPath.contains("creeper")) return "creeper";
        if (blockPath.contains("dragon")) return "dragon";
        if (blockPath.contains("piglin")) return "piglin";
        return "skeleton";
    }

    /**
     * Serializes a Decorated Pot into canonical Visual Semantic NBT JSON.
     */
    public static String serializeDecoratedPot(String back, String left, String right, String front) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"id\":\"minecraft:decorated_pot\",\"sherds\":[");
        appendString(sb, back);
        sb.append(",");
        appendString(sb, left);
        sb.append(",");
        appendString(sb, right);
        sb.append(",");
        appendString(sb, front);
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Serializes a Skull/Head into canonical Visual Semantic NBT JSON.
     *
     * @param kind "skeleton", "wither_skeleton", "zombie", "player", "creeper", "dragon", "piglin"
     * @param rotation 0..15 for floor skull, or null for wall skull
     * @param name Player profile username (or null)
     * @param id Player profile UUID string (or null)
     * @param skinBase64 Base64 encoded texture payload (or null)
     */
    public static String serializeSkull(
            String kind,
            Integer rotation,
            String name,
            String id,
            String skinBase64
    ) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":\"minecraft:skull\"");
        if (kind != null && !kind.isEmpty()) {
            sb.append(",\"kind\":\"").append(escape(kind)).append("\"");
        }
        if (rotation != null) {
            sb.append(",\"rotation\":").append(rotation);
        }

        String skinUrl = extractSkinUrlFromBase64(skinBase64);
        boolean hasProfile = (name != null && !name.isEmpty())
                || (id != null && !id.isEmpty())
                || (skinUrl != null && !skinUrl.isEmpty())
                || (skinBase64 != null && !skinBase64.isEmpty());

        if (hasProfile) {
            sb.append(",\"profile\":{");
            boolean first = true;
            if (name != null && !name.isEmpty()) {
                sb.append("\"name\":\"").append(escape(name)).append("\"");
                first = false;
            }
            if (id != null && !id.isEmpty()) {
                if (!first) sb.append(",");
                sb.append("\"id\":\"").append(escape(id)).append("\"");
                first = false;
            }
            if (skinUrl != null && !skinUrl.isEmpty()) {
                if (!first) sb.append(",");
                sb.append("\"skin_url\":\"").append(escape(skinUrl)).append("\"");
                first = false;
            }
            if (skinBase64 != null && !skinBase64.isEmpty()) {
                if (!first) sb.append(",");
                sb.append("\"skin_base64\":\"").append(escape(skinBase64)).append("\"");
            }
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Extracts direct texture URL from Minecraft Base64-encoded property JSON.
     * e.g. {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/..."}}}
     */
    public static String extractSkinUrlFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            String json = new String(decoded, StandardCharsets.UTF_8);
            int urlIdx = json.indexOf("\"url\":\"");
            if (urlIdx != -1) {
                int start = urlIdx + 7;
                int end = json.indexOf("\"", start);
                if (end != -1) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void appendString(StringBuilder sb, String str) {
        if (str != null && !str.isEmpty()) {
            sb.append("\"").append(escape(str)).append("\"");
        } else {
            sb.append("\"\"");
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
