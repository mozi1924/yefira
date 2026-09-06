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

    private String host = "0.0.0.0";
    private int port = 8765;
    private boolean autoStartOnWorldLoad = false;
    private long maxVolumeSoftLimit = 262144L; // Default 64x64x64 = 262,144 blocks
    private int maxSideSoftLimit = 256;        // Default 256 max single side length

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

            instance = cfg;
            Yefira.LOGGER.info("Loaded Yefira config: host={}, port={}, autoStart={}, maxVolumeSoftLimit={}, maxSideSoftLimit={}",
                    instance.host, instance.port, instance.autoStartOnWorldLoad, instance.maxVolumeSoftLimit, instance.maxSideSoftLimit);
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
}
