package dev.simplesync.cloud;

import dev.simplesync.sync.SyncStatus;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps an InputStream to report transfer progress to the CloudSyncManager status bar.
 */
public class ProgressInputStream extends FilterInputStream {

    private final long totalBytes;
    private final String label;
    private final boolean upload;
    private long bytesRead;
    private int lastPercent = -1;

    public ProgressInputStream(InputStream in, long totalBytes, String label, boolean upload) {
        this(in, totalBytes, label, upload, 0);
    }

    public ProgressInputStream(InputStream in, long totalBytes, String label, boolean upload, long initialOffset) {
        super(in);
        this.totalBytes = totalBytes;
        this.label = label;
        this.upload = upload;
        this.bytesRead = initialOffset;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) updateProgress(1);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read != -1) updateProgress(read);
        return read;
    }

    private void updateProgress(long delta) {
        bytesRead += delta;
        if (totalBytes <= 0) return;
        int percent = (int) ((bytesRead * 100) / totalBytes);
        if (percent != lastPercent) {
            lastPercent = percent;
            SyncStatus status = upload ? SyncStatus.UPLOADING : SyncStatus.DOWNLOADING;
            CloudSyncManager.getInstance().setStatus(status, label + " (" + percent + "%)");
        }
    }
}
