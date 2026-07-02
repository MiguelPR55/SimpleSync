package dev.simplesync.sync;

@FunctionalInterface
public interface ConflictCallback {
    void onConflict(String worldName, long localTimestamp, long cloudTimestamp, Runnable onUseCloud, Runnable onKeepLocal);
}
