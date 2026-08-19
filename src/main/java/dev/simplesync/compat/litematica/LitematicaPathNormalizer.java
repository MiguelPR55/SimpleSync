package dev.simplesync.compat.litematica;

import com.google.gson.*;
import dev.simplesync.util.SyncLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles bidirectional normalization and localization of schematic file paths in Litematica configuration files.
 * Ensures that schematic placements, entity toggles, rotation, origin, and all properties are 100% preserved
 * across different operating systems (Windows, Linux, macOS) and launcher directories.
 */
public class LitematicaPathNormalizer {

    public static final String SCHEMATICS_PORTABLE_PREFIX = "__SCHEMATICS__/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Set<String> PATH_FIELD_KEYS = Set.of(
            "file", "schematic_file", "schematic_path", "schematicPath", "schematic",
            "schematicname", "schematic_name", "schematicfilename", "schematic_file_name",
            "schematicfile", "filename", "fileName", "path", "source_file", "target_file"
    );

    private static final List<String> SCHEMATIC_EXTENSIONS = List.of(
            ".litematic", ".schematic", ".schem", ".nbt"
    );

    /**
     * Checks if a relative path corresponds to a Litematica configuration file that may contain schematic paths.
     */
    public static boolean isLitematicaConfigFile(String relativePath) {
        if (relativePath == null) return false;
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("config/litematica/") || normalized.equals("config/litematica.json");
    }

    /**
     * Converts local absolute paths in a Litematica JSON string into portable relative representations.
     * All other properties (enabled, ignoreEntities, origin, rotation, mirror, subRegions, etc.) are kept intact.
     *
     * @param jsonContent    The raw JSON string from a Litematica config file.
     * @param schematicsDir  The local schematics directory (e.g. .minecraft/schematics).
     * @param gameRootDir    The game root directory (e.g. .minecraft).
     * @return The portable JSON string.
     */
    public static String toPortableJson(String jsonContent, Path schematicsDir, Path gameRootDir) {
        if (jsonContent == null || jsonContent.isBlank()) return jsonContent;

        try {
            JsonElement root = JsonParser.parseString(jsonContent);
            boolean modified = transformTree(root, schematicsDir, gameRootDir, LitematicaPathNormalizer::makePortablePath);
            return modified ? GSON.toJson(root) : jsonContent;
        } catch (Exception e) {
            SyncLogger.warn("[SimpleSync] Failed to normalize Litematica JSON to portable format: {}", e.getMessage());
            return jsonContent;
        }
    }

    /**
     * Converts portable or foreign absolute paths in a Litematica JSON string into valid local absolute paths.
     * All other properties (enabled, ignoreEntities, origin, rotation, mirror, subRegions, etc.) are kept intact.
     *
     * @param jsonContent    The portable or foreign JSON string.
     * @param schematicsDir  The local schematics directory (e.g. .minecraft/schematics).
     * @param gameRootDir    The game root directory (e.g. .minecraft).
     * @return The localized JSON string with valid absolute paths for the local system.
     */
    public static String toLocalJson(String jsonContent, Path schematicsDir, Path gameRootDir) {
        if (jsonContent == null || jsonContent.isBlank()) return jsonContent;

        try {
            JsonElement root = JsonParser.parseString(jsonContent);
            boolean modified = transformTree(root, schematicsDir, gameRootDir, LitematicaPathNormalizer::makeLocalPath);
            return modified ? GSON.toJson(root) : jsonContent;
        } catch (Exception e) {
            SyncLogger.warn("[SimpleSync] Failed to localize Litematica JSON to local paths: {}", e.getMessage());
            return jsonContent;
        }
    }

    /**
     * Auto-heals a local Litematica configuration file if any schematic file paths inside are invalid or broken.
     *
     * @param configFile     The path to the local Litematica json file.
     * @param schematicsDir  The local schematics directory.
     * @return true if the file was modified and updated.
     */
    public static boolean autoHealFile(Path configFile, Path schematicsDir) {
        if (!Files.isRegularFile(configFile)) return false;
        try {
            String content = Files.readString(configFile);
            Path gameRoot = schematicsDir.getParent() != null ? schematicsDir.getParent() : schematicsDir;
            String localized = toLocalJson(content, schematicsDir, gameRoot);
            if (!content.equals(localized)) {
                Files.writeString(configFile, localized);
                SyncLogger.info("[SimpleSync] Auto-healed Litematica paths in: {}", configFile.getFileName());
                return true;
            }
        } catch (Exception e) {
            SyncLogger.warn("[SimpleSync] Failed to auto-heal Litematica config file {}: {}", configFile, e.getMessage());
        }
        return false;
    }

    @FunctionalInterface
    private interface PathTransformer {
        String transform(String originalPath, Path schematicsDir, Path gameRootDir);
    }

    private static boolean transformTree(JsonElement element, Path schematicsDir, Path gameRootDir, PathTransformer transformer) {
        if (element == null) return false;
        boolean modified = false;

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                JsonElement val = entry.getValue();

                if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                    String strVal = val.getAsString();
                    if (isPathCandidate(key, strVal)) {
                        String transformed = transformer.transform(strVal, schematicsDir, gameRootDir);
                        if (!transformed.equals(strVal)) {
                            obj.addProperty(key, transformed);
                            modified = true;
                        }
                    }
                } else if (val.isJsonObject() || val.isJsonArray()) {
                    modified |= transformTree(val, schematicsDir, gameRootDir, transformer);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    String strVal = item.getAsString();
                    if (isPathCandidate("", strVal)) {
                        String transformed = transformer.transform(strVal, schematicsDir, gameRootDir);
                        if (!transformed.equals(strVal)) {
                            array.set(i, new JsonPrimitive(transformed));
                            modified = true;
                        }
                    }
                } else if (item.isJsonObject() || item.isJsonArray()) {
                    modified |= transformTree(item, schematicsDir, gameRootDir, transformer);
                }
            }
        }

        return modified;
    }

    // ─── Path Resolution Logic ────────────────────────────────────────────

    private static boolean isPathCandidate(String key, String value) {
        if (value == null || value.isBlank()) return false;
        if (value.startsWith(SCHEMATICS_PORTABLE_PREFIX)) return true;

        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (PATH_FIELD_KEYS.contains(lowerKey)) return true;

        String lowerVal = value.toLowerCase(Locale.ROOT);
        for (String ext : SCHEMATIC_EXTENSIONS) {
            if (lowerVal.endsWith(ext)) return true;
        }

        return lowerVal.contains("/schematics/") || lowerVal.contains("\\schematics\\");
    }

    public static String makePortablePath(String originalPath, Path schematicsDir, Path gameRootDir) {
        if (originalPath == null || originalPath.isBlank()) return originalPath;
        if (originalPath.startsWith(SCHEMATICS_PORTABLE_PREFIX)) return originalPath;

        String normalized = originalPath.replace('\\', '/');

        // Check if path is relative to local schematics dir
        if (schematicsDir != null) {
            try {
                Path absSchem = schematicsDir.toAbsolutePath().normalize();
                String schemStr = absSchem.toString().replace('\\', '/');
                if (normalized.startsWith(schemStr)) {
                    String sub = normalized.substring(schemStr.length());
                    while (sub.startsWith("/")) sub = sub.substring(1);
                    return SCHEMATICS_PORTABLE_PREFIX + sub;
                }
            } catch (Exception ignored) {}
        }

        // Search for "/schematics/" marker in path
        int idx = normalized.toLowerCase(Locale.ROOT).indexOf("/schematics/");
        if (idx >= 0) {
            String sub = normalized.substring(idx + "/schematics/".length());
            while (sub.startsWith("/")) sub = sub.substring(1);
            return SCHEMATICS_PORTABLE_PREFIX + sub;
        }

        if (normalized.toLowerCase(Locale.ROOT).startsWith("schematics/")) {
            String sub = normalized.substring("schematics/".length());
            while (sub.startsWith("/")) sub = sub.substring(1);
            return SCHEMATICS_PORTABLE_PREFIX + sub;
        }

        // If it's just a filename or relative path ending in a schematic extension
        for (String ext : SCHEMATIC_EXTENSIONS) {
            if (normalized.toLowerCase(Locale.ROOT).endsWith(ext)) {
                int lastSlash = normalized.lastIndexOf('/');
                String fileName = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
                return SCHEMATICS_PORTABLE_PREFIX + fileName;
            }
        }

        return originalPath;
    }

    public static String makeLocalPath(String portableOrForeignPath, Path schematicsDir, Path gameRootDir) {
        if (portableOrForeignPath == null || portableOrForeignPath.isBlank()) return portableOrForeignPath;
        if (schematicsDir == null) return portableOrForeignPath;

        Path absSchematicsDir = schematicsDir.toAbsolutePath().normalize();

        if (portableOrForeignPath.startsWith(SCHEMATICS_PORTABLE_PREFIX)) {
            String rel = portableOrForeignPath.substring(SCHEMATICS_PORTABLE_PREFIX.length());
            // Sanitize relative path to avoid path traversal
            rel = rel.replace('\\', '/').replace("..", "");
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path resolved = absSchematicsDir.resolve(rel).normalize();
            if (!resolved.startsWith(absSchematicsDir)) {
                resolved = absSchematicsDir;
            }
            return resolved.toString();
        }

        // Handle foreign paths from another OS / machine
        String normalized = portableOrForeignPath.replace('\\', '/');

        // If it already exists on this machine exactly as-is, preserve it
        try {
            Path testPath = Path.of(portableOrForeignPath);
            if (Files.exists(testPath)) {
                return testPath.toAbsolutePath().toString();
            }
        } catch (Throwable ignored) {}

        // Extract relative schematic subpath
        String relSubpath = null;
        int idx = normalized.toLowerCase(Locale.ROOT).indexOf("/schematics/");
        if (idx >= 0) {
            relSubpath = normalized.substring(idx + "/schematics/".length());
        } else if (normalized.toLowerCase(Locale.ROOT).startsWith("schematics/")) {
            relSubpath = normalized.substring("schematics/".length());
        } else {
            int lastSlash = normalized.lastIndexOf('/');
            relSubpath = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
        }

        relSubpath = relSubpath.replace("..", "");
        while (relSubpath.startsWith("/")) {
            relSubpath = relSubpath.substring(1);
        }

        Path candidate = absSchematicsDir.resolve(relSubpath).normalize();
        if (!candidate.startsWith(absSchematicsDir)) {
            candidate = absSchematicsDir;
        }
        if (Files.exists(candidate)) {
            return candidate.toString();
        }

        // Auto-heal by searching in schematicsDir for matching filename if placed in a subfolder
        String fileNameOnly = candidate.getFileName() != null ? candidate.getFileName().toString() : "";
        if (!fileNameOnly.isEmpty()) {
            Path found = findSchematicFile(absSchematicsDir, fileNameOnly);
            if (found != null) {
                return found.toString();
            }
        }

        return candidate.toString();
    }

    private static Path findSchematicFile(Path baseDir, String fileName) {
        if (!Files.isDirectory(baseDir) || fileName == null || fileName.isBlank()) return null;
        try (var stream = Files.walk(baseDir, 5)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
