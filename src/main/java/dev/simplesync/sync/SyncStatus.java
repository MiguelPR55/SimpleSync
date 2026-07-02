package dev.simplesync.sync;

public enum SyncStatus {
    IDLE("idle"),
    AUTHENTICATING("authenticating"),
    CHECKING("checking"),
    DOWNLOADING("downloading"),
    UPLOADING("uploading"),
    COMPRESSING("compressing"),
    EXTRACTING("extracting"),
    DONE("done"),
    ERROR("error"),
    CONFLICT("conflict");

    private final String key;

    SyncStatus(String key) {
        this.key = key;
    }

    public String getTranslationKey() {
        return "simplesync.status." + key;
    }
}
