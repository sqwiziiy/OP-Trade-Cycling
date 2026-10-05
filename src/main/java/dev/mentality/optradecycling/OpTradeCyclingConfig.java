package dev.mentality.optradecycling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class OpTradeCyclingConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("optradecycling.json");

    /**
     * DANGEROUS: when enabled, villagers that have already been traded with may
     * be rerolled. Their trade XP and villager level are reset to novice.
     */
    boolean dangerousBypassUsedTrades = false;

    static OpTradeCyclingConfig load() {
        OpTradeCyclingConfig defaults = new OpTradeCyclingConfig();

        try {
            if (Files.notExists(CONFIG_PATH)) {
                save(defaults);
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                OpTradeCyclingConfig config = GSON.fromJson(reader, OpTradeCyclingConfig.class);
                return config != null ? config : defaults;
            }
        } catch (Exception exception) {
            System.err.println("[OP Trade Cycling] Failed to read config; safe mode is enabled.");
            exception.printStackTrace();
            return defaults;
        }
    }

    private static void save(OpTradeCyclingConfig config) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception exception) {
            System.err.println("[OP Trade Cycling] Failed to create default config.");
            exception.printStackTrace();
        }
    }

    private OpTradeCyclingConfig() {
    }
}
