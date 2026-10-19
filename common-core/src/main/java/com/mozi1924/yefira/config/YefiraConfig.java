package com.mozi1924.yefira.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.platform.Services;

import java.nio.file.Files;
import java.nio.file.Path;

public class YefiraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path getConfigPath() {
        try {
            Path dir = Services.PLATFORM.getConfigDirectory();
            if (dir != null) {
                return dir.resolve("yefira.json");
            }
        } catch (Throwable ignored) {}
        return java.nio.file.Paths.get("config", "yefira.json");
    }

    private static YefiraConfig instance = new YefiraConfig();

    // Bind to loopback by default. A non-loopback host requires a non-empty
    // authToken; the WebSocket server refuses to start otherwise.
    private String host = "127.0.0.1";
    private int port = 8765;
    private boolean autoStartOnWorldLoad = false;
    private long maxVolumeSoftLimit = 262144L; // Default 64x64x64 = 262,144 blocks
    private int maxSideSoftLimit = 256;        // Default 256 max single side length
    private String authToken = "";
    /**
     * When true and the server is bound to a loopback address, clients may
     * connect without presenting authToken. This is convenient for a purely
     * local Blender connection while keeping non-loopback binds token-gated.
     */
    private boolean allowLoopbackWithoutToken = false;

    public static YefiraConfig getInstance() {
        return instance;
    }

    public static void load() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            save();
            return;
        }

        try {
            String json = Files.readString(configPath);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            YefiraConfig cfg = new YefiraConfig();
            if (obj.has("host")) {
                cfg.host = obj.get("host").getAsString();
            }
            if (obj.has("port")) {
                int p = obj.get("port").getAsInt();
                if (p >= 1024 && p <= 65535) {
                    cfg.port = p;
                }
            }
            if (obj.has("autoStartOnWorldLoad")) {
                cfg.autoStartOnWorldLoad = obj.get("autoStartOnWorldLoad").getAsBoolean();
            }
            if (obj.has("maxVolumeSoftLimit")) {
                long mv = obj.get("maxVolumeSoftLimit").getAsLong();
                if (mv > 0) {
                    cfg.maxVolumeSoftLimit = mv;
                }
            }
            if (obj.has("maxSideSoftLimit")) {
                int ms = obj.get("maxSideSoftLimit").getAsInt();
                if (ms > 0) {
                    cfg.maxSideSoftLimit = ms;
                }
            }
            if (obj.has("authToken")) {
                cfg.authToken = obj.get("authToken").getAsString().trim();
            }
            if (obj.has("allowLoopbackWithoutToken")) {
                cfg.allowLoopbackWithoutToken = obj.get("allowLoopbackWithoutToken").getAsBoolean();
            }

            instance = cfg;
            Yefira.LOGGER.info("Loaded Yefira config: host={}, port={}, autoStart={}, maxVolumeSoftLimit={}, maxSideSoftLimit={}, authTokenConfigured={}, allowLoopbackWithoutToken={}",
                    instance.host, instance.port, instance.autoStartOnWorldLoad, instance.maxVolumeSoftLimit, instance.maxSideSoftLimit,
                    instance.authToken != null && !instance.authToken.isEmpty(), instance.allowLoopbackWithoutToken);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to load Yefira config, falling back to defaults", e);
            instance = new YefiraConfig();
            save();
        }
    }

    public static void save() {
        try {
            Path configPath = getConfigPath();
            JsonObject obj = new JsonObject();
            obj.addProperty("host", instance.host);
            obj.addProperty("port", instance.port);
            obj.addProperty("autoStartOnWorldLoad", instance.autoStartOnWorldLoad);
            obj.addProperty("maxVolumeSoftLimit", instance.maxVolumeSoftLimit);
            obj.addProperty("maxSideSoftLimit", instance.maxSideSoftLimit);
            obj.addProperty("authToken", instance.authToken == null ? "" : instance.authToken);
            obj.addProperty("allowLoopbackWithoutToken", instance.allowLoopbackWithoutToken);

            if (configPath.getParent() != null && !Files.exists(configPath.getParent())) {
                Files.createDirectories(configPath.getParent());
            }
            Files.writeString(configPath, GSON.toJson(obj));
            Yefira.LOGGER.info("Saved Yefira config to {}", configPath);
        } catch (Exception e) {
            Yefira.LOGGER.error("Failed to save Yefira config", e);
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            this.host = host.trim();
        }
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (port >= 1024 && port <= 65535) {
            this.port = port;
        }
    }

    public boolean isAutoStartOnWorldLoad() {
        return autoStartOnWorldLoad;
    }

    public void setAutoStartOnWorldLoad(boolean autoStartOnWorldLoad) {
        this.autoStartOnWorldLoad = autoStartOnWorldLoad;
    }

    public long getMaxVolumeSoftLimit() {
        return maxVolumeSoftLimit;
    }

    public void setMaxVolumeSoftLimit(long maxVolumeSoftLimit) {
        if (maxVolumeSoftLimit > 0) {
            this.maxVolumeSoftLimit = maxVolumeSoftLimit;
        }
    }

    public int getMaxSideSoftLimit() {
        return maxSideSoftLimit;
    }

    public void setMaxSideSoftLimit(int maxSideSoftLimit) {
        if (maxSideSoftLimit > 0) {
            this.maxSideSoftLimit = maxSideSoftLimit;
        }
    }

    public String getAuthToken() {
        return authToken == null ? "" : authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    public boolean isAllowLoopbackWithoutToken() {
        return allowLoopbackWithoutToken;
    }

    public void setAllowLoopbackWithoutToken(boolean allowLoopbackWithoutToken) {
        this.allowLoopbackWithoutToken = allowLoopbackWithoutToken;
    }
}
