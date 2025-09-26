package dev.fluffix.sentinel.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Map;

public class JsonFileBuilder {
    private final ObjectMapper mapper;
    private ObjectNode rootNode;

    private static final String COPYRIGHT_HEADER =
            "# Copyright © FluffixYT\n" +
                    "#   Alle Rechte vorbehalten.\n" +
                    "#   Diese Konfiguration wurde von FluffixYT erstellt und unterliegt dem Urheberrecht. Es ist nicht gestattet, diese Config ganz oder in Teilen als eigenes Projekt auszugeben, weiterzuverbreiten oder zu verkaufen – weder mit noch ohne Änderungen – ohne ausdrückliche schriftliche Genehmigung von FluffixYT.\n" +
                    "#\n" +
                    "#   Zuwiderhandlungen können rechtliche Schritte nach sich ziehen.\n" +
                    "#   Discord https://discord.gg/wFqCUrtCwp\n\n";

    public JsonFileBuilder() {
        mapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .build();
        mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        rootNode = mapper.createObjectNode();
    }

    public JsonFileBuilder add(String key, String value) { rootNode.put(key, value); return this; }
    public JsonFileBuilder add(String key, int value)     { rootNode.put(key, value); return this; }
    public JsonFileBuilder add(String key, boolean value) { rootNode.put(key, value); return this; }
    public JsonFileBuilder addObject(String key, ObjectNode objectNode) { rootNode.set(key, objectNode); return this; }

    public void build(String filePath) throws IOException {
        File file = new File(filePath);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8)) {
            fw.write(COPYRIGHT_HEADER);
            fw.write(json);
        }
    }

    public void loadFromFile(File file) throws IOException {
        try {
            JsonNode node = mapper.readTree(file);
            if (node != null && node.isObject()) {
                rootNode = (ObjectNode) node;
                return;
            }
        } catch (Exception ignored) {
        }

        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        JsonNode node = mapper.readTree(sb.toString());
        if (node != null && node.isObject()) {
            rootNode = (ObjectNode) node;
        } else {
            rootNode = mapper.createObjectNode();
        }
    }

    public String getString(String key) { JsonNode n = rootNode.get(key); return n != null ? n.asText() : null; }
    public int getInt(String key)       { JsonNode n = rootNode.get(key); return n != null ? n.asInt() : 0; }
    public boolean getBoolean(String key){ JsonNode n = rootNode.get(key); return n != null && n.asBoolean(); }
    public boolean contains(String key) { return rootNode.has(key); }
    public ObjectNode getRootNode()     { return rootNode; }
    public Iterator<Map.Entry<String, JsonNode>> entries() { return rootNode.fields(); }
}