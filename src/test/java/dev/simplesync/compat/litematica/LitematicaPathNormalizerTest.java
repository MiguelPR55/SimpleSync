package dev.simplesync.compat.litematica;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class LitematicaPathNormalizerTest {

    @TempDir
    Path tempGameDir;

    private Path schematicsDir;

    @BeforeEach
    void setUp() throws IOException {
        schematicsDir = tempGameDir.resolve("schematics");
        Files.createDirectories(schematicsDir);
    }

    @Test
    void testIsLitematicaConfigFile() {
        assertTrue(LitematicaPathNormalizer.isLitematicaConfigFile("config/litematica.json"));
        assertTrue(LitematicaPathNormalizer.isLitematicaConfigFile("config/litematica/world_dim_minecraft_overworld.json"));
        assertTrue(LitematicaPathNormalizer.isLitematicaConfigFile("config\\litematica\\world_dim_minecraft_the_nether.json"));
        assertFalse(LitematicaPathNormalizer.isLitematicaConfigFile("config/tweakeroo.json"));
        assertFalse(LitematicaPathNormalizer.isLitematicaConfigFile("saves/world/level.dat"));
        assertFalse(LitematicaPathNormalizer.isLitematicaConfigFile(null));
    }

    @Test
    void testWindowsPathToPortable() {
        String json = """
                {
                  "placements": [
                    {
                      "name": "iron_farm",
                      "file": "C:\\\\Users\\\\Alice\\\\AppData\\\\Roaming\\\\.minecraft\\\\schematics\\\\farms\\\\iron_farm.litematic",
                      "enabled": false,
                      "ignoreEntities": true,
                      "origin": { "x": 100, "y": 64, "z": -200 },
                      "rotation": "CW_90",
                      "mirror": "NONE"
                    }
                  ]
                }
                """;

        String portable = LitematicaPathNormalizer.toPortableJson(json, schematicsDir, tempGameDir);
        JsonObject parsed = JsonParser.parseString(portable).getAsJsonObject();
        JsonObject placement = parsed.getAsJsonArray("placements").get(0).getAsJsonObject();

        assertEquals(LitematicaPathNormalizer.SCHEMATICS_PORTABLE_PREFIX + "farms/iron_farm.litematic",
                placement.get("file").getAsString());
        assertFalse(placement.get("enabled").getAsBoolean());
        assertTrue(placement.get("ignoreEntities").getAsBoolean());
        assertEquals(100, placement.getAsJsonObject("origin").get("x").getAsInt());
        assertEquals("CW_90", placement.get("rotation").getAsString());
    }

    @Test
    void testLinuxPathToPortable() {
        String json = """
                {
                  "placements": [
                    {
                      "name": "tree_farm",
                      "schematic_file": "/home/bob/.minecraft/schematics/forestry/tree_farm.litematic",
                      "enabled": true,
                      "ignoreEntities": false
                    }
                  ]
                }
                """;

        String portable = LitematicaPathNormalizer.toPortableJson(json, schematicsDir, tempGameDir);
        JsonObject parsed = JsonParser.parseString(portable).getAsJsonObject();
        JsonObject placement = parsed.getAsJsonArray("placements").get(0).getAsJsonObject();

        assertEquals(LitematicaPathNormalizer.SCHEMATICS_PORTABLE_PREFIX + "forestry/tree_farm.litematic",
                placement.get("schematic_file").getAsString());
        assertTrue(placement.get("enabled").getAsBoolean());
        assertFalse(placement.get("ignoreEntities").getAsBoolean());
    }

    @Test
    void testPortableToLocalLinuxOrWindows() {
        String portableJson = """
                {
                  "placements": [
                    {
                      "name": "storage_hall",
                      "file": "__SCHEMATICS__/redstone/storage.litematic",
                      "enabled": false,
                      "ignoreEntities": true
                    }
                  ]
                }
                """;

        String localJson = LitematicaPathNormalizer.toLocalJson(portableJson, schematicsDir, tempGameDir);
        JsonObject parsed = JsonParser.parseString(localJson).getAsJsonObject();
        JsonObject placement = parsed.getAsJsonArray("placements").get(0).getAsJsonObject();

        Path expectedPath = schematicsDir.resolve("redstone/storage.litematic").toAbsolutePath().normalize();
        assertEquals(expectedPath.toString(), placement.get("file").getAsString());
        assertFalse(placement.get("enabled").getAsBoolean());
        assertTrue(placement.get("ignoreEntities").getAsBoolean());
    }

    @Test
    void testForeignPathAutoHealing() throws IOException {
        Path subDir = schematicsDir.resolve("auto_healed");
        Files.createDirectories(subDir);
        Path targetFile = subDir.resolve("sorter.litematic");
        Files.writeString(targetFile, "dummy schematic data");

        // Foreign Windows path received on another machine where it doesn't exist directly
        String foreignJson = """
                {
                  "placements": [
                    {
                      "name": "sorter",
                      "file": "D:\\\\Games\\\\PrismLauncher\\\\instances\\\\1.21\\\\.minecraft\\\\schematics\\\\sorter.litematic",
                      "enabled": true,
                      "ignoreEntities": true
                    }
                  ]
                }
                """;

        String localized = LitematicaPathNormalizer.toLocalJson(foreignJson, schematicsDir, tempGameDir);
        JsonObject parsed = JsonParser.parseString(localized).getAsJsonObject();
        JsonObject placement = parsed.getAsJsonArray("placements").get(0).getAsJsonObject();

        // Should auto-heal to the located file inside schematicsDir
        assertEquals(targetFile.toAbsolutePath().toString(), placement.get("file").getAsString());
        assertTrue(placement.get("enabled").getAsBoolean());
        assertTrue(placement.get("ignoreEntities").getAsBoolean());
    }

    @Test
    void testFullRoundTripPreservation() {
        String initialJson = """
                {
                  "placements": [
                    {
                      "name": "mega_base",
                      "file": "__SCHEMATICS__/base.litematic",
                      "enabled": false,
                      "ignoreEntities": true,
                      "origin": { "x": -500, "y": 12, "z": 800 },
                      "rotation": "CW_180",
                      "mirror": "LEFT_RIGHT",
                      "subRegions": {
                        "main_hub": { "enabled": true },
                        "reactor": { "enabled": false }
                      },
                      "locked": true
                    }
                  ]
                }
                """;

        String localized = LitematicaPathNormalizer.toLocalJson(initialJson, schematicsDir, tempGameDir);
        String backToPortable = LitematicaPathNormalizer.toPortableJson(localized, schematicsDir, tempGameDir);

        JsonObject orig = JsonParser.parseString(initialJson).getAsJsonObject().getAsJsonArray("placements").get(0).getAsJsonObject();
        JsonObject roundTripped = JsonParser.parseString(backToPortable).getAsJsonObject().getAsJsonArray("placements").get(0).getAsJsonObject();

        assertEquals(orig.get("file").getAsString(), roundTripped.get("file").getAsString());
        assertEquals(orig.get("enabled").getAsBoolean(), roundTripped.get("enabled").getAsBoolean());
        assertEquals(orig.get("ignoreEntities").getAsBoolean(), roundTripped.get("ignoreEntities").getAsBoolean());
        assertEquals(orig.get("rotation").getAsString(), roundTripped.get("rotation").getAsString());
        assertEquals(orig.get("mirror").getAsString(), roundTripped.get("mirror").getAsString());
        assertEquals(orig.get("locked").getAsBoolean(), roundTripped.get("locked").getAsBoolean());
        assertEquals(orig.getAsJsonObject("origin").get("x").getAsInt(), roundTripped.getAsJsonObject("origin").get("x").getAsInt());
        assertEquals(orig.getAsJsonObject("subRegions").getAsJsonObject("reactor").get("enabled").getAsBoolean(),
                     roundTripped.getAsJsonObject("subRegions").getAsJsonObject("reactor").get("enabled").getAsBoolean());
    }

    @Test
    void testAutoHealFile() throws IOException {
        Path configDir = tempGameDir.resolve("config").resolve("litematica");
        Files.createDirectories(configDir);
        Path placementFile = configDir.resolve("litematica_myworld_dim_minecraft_overworld.json");

        String brokenJson = """
                {
                  "placements": [
                    {
                      "file": "C:\\\\OldPC\\\\.minecraft\\\\schematics\\\\beacon.litematic"
                    }
                  ]
                }
                """;
        Files.writeString(placementFile, brokenJson);

        boolean healed = LitematicaPathNormalizer.autoHealFile(placementFile, schematicsDir);
        assertTrue(healed);

        String healedContent = Files.readString(placementFile);
        JsonObject parsed = JsonParser.parseString(healedContent).getAsJsonObject();
        String fileVal = parsed.getAsJsonArray("placements").get(0).getAsJsonObject().get("file").getAsString();
        Path expectedPath = schematicsDir.resolve("beacon.litematic").toAbsolutePath().normalize();
        assertEquals(expectedPath.toString(), Path.of(fileVal).toAbsolutePath().normalize().toString());
    }

    @Test
    void testPathTraversalSanitization() {
        String attackJson = """
                {
                  "placements": [
                    {
                      "file": "__SCHEMATICS__/../../secret.txt"
                    }
                  ]
                }
                """;

        String local = LitematicaPathNormalizer.toLocalJson(attackJson, schematicsDir, tempGameDir);
        JsonObject parsed = JsonParser.parseString(local).getAsJsonObject();
        String fileVal = parsed.getAsJsonArray("placements").get(0).getAsJsonObject().get("file").getAsString();

        assertTrue(fileVal.startsWith(schematicsDir.toAbsolutePath().normalize().toString()));
        assertFalse(fileVal.contains(".."));
    }

    @Test
    void testMalformedJsonAndNullHandling() {
        assertEquals("not a json string {{{", LitematicaPathNormalizer.toPortableJson("not a json string {{{", schematicsDir, tempGameDir));
        assertEquals("not a json string {{{", LitematicaPathNormalizer.toLocalJson("not a json string {{{", schematicsDir, tempGameDir));
        assertNull(LitematicaPathNormalizer.toPortableJson(null, schematicsDir, tempGameDir));
        assertNull(LitematicaPathNormalizer.toLocalJson(null, schematicsDir, tempGameDir));
        assertEquals("", LitematicaPathNormalizer.toPortableJson("", schematicsDir, tempGameDir));
    }
}
