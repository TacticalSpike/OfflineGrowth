
package com.example.offlinegrowth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    public int maxOfflineHours = 24;         // hard cap
    public int ticksPerStage = 200;         // ~5 in-game minutes per growth stage
    public boolean affectWheat = true;
    public boolean affectCarrots = true;
    public boolean affectPotatoes = true;
    public boolean affectBeetroots = true;
    public boolean affectNetherWart = false; // off by default
    public boolean affectSweetBerry = false; // off by default (can be prickly in HC)

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config load(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path cfg = configDir.resolve("offline_growth.json");
            if (Files.exists(cfg)) {
                return GSON.fromJson(Files.readString(cfg), Config.class);
            } else {
                Config c = new Config();
                Files.writeString(cfg, GSON.toJson(c));
                return c;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new Config();
        }
    }
}
