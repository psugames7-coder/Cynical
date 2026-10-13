package com.kephale.deathban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean deathBanEnabled = true;
    public int maxDeaths = 5;
    public int reviveDeaths = 3;
    public double steveHeadChance = 0.30;
    public int eliminationChance = 20;
    public boolean hideInvisibleKillers = true;
    public boolean ownDeathMessages = true;
    public boolean hideBotConnectionMessages = true;

    public boolean pearlCatchEnabled = true;
    public double pearlCollisionRadius = 2.5;
    public double pearlMinFlightDistance = 3.0;

    public boolean tabListNearbyOnly = true;

    private transient Path file;

    public static ModConfig load(Path configDir) {
        Path f = configDir.resolve("config.json");
        ModConfig cfg;
        try {
            if (Files.exists(f)) {
                cfg = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), ModConfig.class);
                if (cfg == null) cfg = new ModConfig();
            } else {
                cfg = new ModConfig();
            }
        } catch (Exception e) {
            DeathBanMod.LOGGER.error("Could not read config.json, using defaults", e);
            cfg = new ModConfig();
        }
        cfg.file = f;
        cfg.pearlCatchEnabled = true;
        if (cfg.pearlCollisionRadius < 0.5) cfg.pearlCollisionRadius = 2.5;
        if (cfg.pearlMinFlightDistance < 0) cfg.pearlMinFlightDistance = 3.0;
        if (cfg.eliminationChance < 0) cfg.eliminationChance = 0;
        if (cfg.eliminationChance > 100) cfg.eliminationChance = 100;
        cfg.save();
        return cfg;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DeathBanMod.LOGGER.error("Could not save config.json", e);
        }
    }
}
