package com.kephale.deathban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent death records.
 *
 * <p>Stored as JSON at {@code config/deathban/players.json} so it can be opened,
 * edited and backed up by hand. Also knows how to import the Bukkit-era
 * {@code players.yml} so data carries over from the Paper server.
 */
public final class PlayerDataStore {

    public static final class Entry {
        public int deaths;
        public String name;
        public long lastDeath;
        public boolean tokenRevived;

        public Entry() {}

        public Entry(String name) { this.name = name; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    private final Path file;
    private final Map<UUID, Entry> data = new HashMap<>();

    public PlayerDataStore(Path configDir) {
        this.file = configDir.resolve("players.json");
    }

    // ---------- load / save ----------

    public void load() {
        data.clear();
        try {
            if (!Files.exists(file)) { save(); return; }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Entry> raw = GSON.fromJson(json, MAP_TYPE);
            if (raw == null) return;
            for (Map.Entry<String, Entry> e : raw.entrySet()) {
                try { data.put(UUID.fromString(e.getKey()), e.getValue()); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            DeathBanMod.LOGGER.error("Could not load players.json", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Entry> raw = new HashMap<>();
            for (Map.Entry<UUID, Entry> e : data.entrySet()) {
                raw.put(e.getKey().toString(), e.getValue());
            }
            Files.writeString(file, GSON.toJson(raw, MAP_TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DeathBanMod.LOGGER.error("Could not save players.json", e);
        }
    }

    // ---------- access ----------

    public Entry get(UUID id) { return data.get(id); }

    public Entry getOrCreate(UUID id, String name) {
        Entry e = data.get(id);
        if (e == null) {
            e = new Entry(name);
            data.put(id, e);
        }
        // Never overwrite an existing name - it may be a nick, and clobbering it
        // corrupts name-based lookups.
        if (name != null && (e.name == null || e.name.isEmpty())) e.name = name;
        return e;
    }

    public Map<UUID, Entry> all() { return data; }

    public UUID findByName(String name) {
        for (Map.Entry<UUID, Entry> e : data.entrySet()) {
            if (e.getValue().name != null && e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public List<UUID> banned(int threshold) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : data.entrySet()) {
            if (e.getValue().deaths >= threshold) out.add(e.getKey());
        }
        return out;
    }

    /** Stable record id for a nick that may not be a real account. */
    public static UUID offlineIdFor(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- transfer ----------

    /**
     * Import the Bukkit plugin's {@code players.yml}. Deliberately a small
     * hand-rolled parser rather than a YAML dependency - the file shape is fixed:
     *
     * <pre>
     * players:
     *   &lt;uuid&gt;:
     *     deaths: 3
     *     name: Someone
     *     lastDeath: 1783040219661
     * </pre>
     *
     * @return number of records imported
     */
    public int importLegacyYaml(Path yaml, boolean overwrite) throws IOException {
        List<String> lines = Files.readAllLines(yaml, StandardCharsets.UTF_8);
        int imported = 0;
        UUID current = null;
        Entry entry = null;

        for (String rawLine : lines) {
            String line = rawLine.replace("\t", "    ");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.equals("players:")) continue;

            int indent = line.length() - line.stripLeading().length();

            // A UUID key sits at the first indent level and ends with ':'
            if (indent <= 2 && trimmed.endsWith(":")) {
                if (current != null && entry != null) {
                    if (overwrite || !data.containsKey(current)) { data.put(current, entry); imported++; }
                }
                String key = trimmed.substring(0, trimmed.length() - 1).trim();
                try {
                    current = UUID.fromString(key);
                    entry = new Entry();
                } catch (IllegalArgumentException ex) {
                    current = null; entry = null;
                }
                continue;
            }

            if (entry == null) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String field = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }

            switch (field) {
                case "deaths" -> { try { entry.deaths = Integer.parseInt(value); } catch (NumberFormatException ignored) {} }
                case "name" -> entry.name = value;
                case "lastDeath" -> { try { entry.lastDeath = Long.parseLong(value); } catch (NumberFormatException ignored) {} }
                case "tokenRevived" -> entry.tokenRevived = Boolean.parseBoolean(value);
                default -> { }
            }
        }
        if (current != null && entry != null) {
            if (overwrite || !data.containsKey(current)) { data.put(current, entry); imported++; }
        }
        if (imported > 0) save();
        return imported;
    }

    /** Write a timestamped copy next to the live file. */
    public Path export() throws IOException {
        Files.createDirectories(file.getParent());
        Path out = file.getParent().resolve("players-export-" + System.currentTimeMillis() + ".json");
        Map<String, Entry> raw = new HashMap<>();
        for (Map.Entry<UUID, Entry> e : data.entrySet()) raw.put(e.getKey().toString(), e.getValue());
        Files.writeString(out, GSON.toJson(raw, MAP_TYPE), StandardCharsets.UTF_8);
        return out;
    }

    /** Merge another JSON export into this store. */
    public int importJson(Path json, boolean overwrite) throws IOException {
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Map<String, Entry> raw = GSON.fromJson(text, MAP_TYPE);
        if (raw == null) return 0;
        int n = 0;
        for (Map.Entry<String, Entry> e : raw.entrySet()) {
            try {
                UUID id = UUID.fromString(e.getKey());
                if (overwrite || !data.containsKey(id)) { data.put(id, e.getValue()); n++; }
            } catch (IllegalArgumentException ignored) {}
        }
        if (n > 0) save();
        return n;
    }
}
