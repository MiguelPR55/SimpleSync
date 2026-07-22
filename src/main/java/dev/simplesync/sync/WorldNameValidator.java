package dev.simplesync.sync;

import java.util.regex.Pattern;

/**
 * Validates world names and archive entry names for security.
 */
public final class WorldNameValidator {

    private static final Pattern WINDOWS_RESERVED = Pattern.compile(
            "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);

    private WorldNameValidator() {}

    public static boolean isWorldNameSafe(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) return false;
        if (worldName.length() > 64) return false;
        if (worldName.contains("..") || worldName.contains("/") || worldName.contains("\\") || worldName.indexOf('\0') >= 0) return false;
        for (int i = 0; i < worldName.length(); i++) {
            char c = worldName.charAt(i);
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?' || c == '*' || c < 32) return false;
        }
        if (WINDOWS_RESERVED.matcher(worldName.trim()).matches()) return false;
        return !worldName.endsWith(".") && !worldName.endsWith(" ");
    }

    public static boolean isArchiveEntryNameSafe(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) return false;
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf(':') >= 0
                || normalized.equals(".") || normalized.equals("..")
                || normalized.startsWith("../") || normalized.contains("/../")
                || normalized.endsWith("/..")) return false;
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return false;
            if (WINDOWS_RESERVED.matcher(part).matches()) return false;
        }
        return true;
    }
}
