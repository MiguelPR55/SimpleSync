package dev.simplesync.sync;

public record WorldMetadata(
    String worldName,
    long lastModified,
    long sizeBytes,
    String cloudFileId
) {
    public WorldMetadata(String worldName, long lastModified, long sizeBytes) {
        this(worldName, lastModified, sizeBytes, null);
    }
}
