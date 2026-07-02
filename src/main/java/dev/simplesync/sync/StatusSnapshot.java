package dev.simplesync.sync;

public record StatusSnapshot(SyncStatus status, String detail, long timestamp) {
}
