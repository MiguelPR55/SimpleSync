package dev.simplesync.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WorldNameValidatorTest {

    @Test
    void testIsWorldNameSafe_ValidNames() {
        assertTrue(WorldNameValidator.isWorldNameSafe("Survival_2026"));
        assertTrue(WorldNameValidator.isWorldNameSafe("My World 1"));
        assertTrue(WorldNameValidator.isWorldNameSafe("Mundo_Español"));
        assertTrue(WorldNameValidator.isWorldNameSafe("123-abc"));
    }

    @Test
    void testIsWorldNameSafe_InvalidNames() {
        assertFalse(WorldNameValidator.isWorldNameSafe(null));
        assertFalse(WorldNameValidator.isWorldNameSafe(""));
        assertFalse(WorldNameValidator.isWorldNameSafe("   "));
        assertFalse(WorldNameValidator.isWorldNameSafe("../world"));
        assertFalse(WorldNameValidator.isWorldNameSafe("world/sub"));
        assertFalse(WorldNameValidator.isWorldNameSafe("world\\sub"));
        assertFalse(WorldNameValidator.isWorldNameSafe("world:name"));
        assertFalse(WorldNameValidator.isWorldNameSafe("world*"));
        assertFalse(WorldNameValidator.isWorldNameSafe("CON"));
        assertFalse(WorldNameValidator.isWorldNameSafe("NUL"));
        assertFalse(WorldNameValidator.isWorldNameSafe("COM1"));
        assertFalse(WorldNameValidator.isWorldNameSafe("LPT1"));
        assertFalse(WorldNameValidator.isWorldNameSafe("world."));
        assertFalse(WorldNameValidator.isWorldNameSafe("world "));
        assertFalse(WorldNameValidator.isWorldNameSafe("a".repeat(65))); // Exceeds 64 chars
    }

    @Test
    void testIsArchiveEntryNameSafe_ValidEntries() {
        assertTrue(WorldNameValidator.isArchiveEntryNameSafe("level.dat"));
        assertTrue(WorldNameValidator.isArchiveEntryNameSafe("region/r.0.0.mca"));
        assertTrue(WorldNameValidator.isArchiveEntryNameSafe("entities/r.0.0.mca"));
        assertTrue(WorldNameValidator.isArchiveEntryNameSafe("dimensions/minecraft/the_nether/data/raids.dat"));
    }

    @Test
    void testIsArchiveEntryNameSafe_ZipSlipAndMaliciousEntries() {
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe(null));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe(""));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("/etc/passwd"));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("../outside.dat"));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("region/../../outside.dat"));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("region/.."));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("C:/Windows/System32/calc.exe"));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("CON/level.dat"));
        assertFalse(WorldNameValidator.isArchiveEntryNameSafe("folder./level.dat"));
    }
}
