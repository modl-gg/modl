package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.api.http.request.ExportProgressRequest;

import java.util.concurrent.atomic.AtomicInteger;

final class ExportProgress {
    private static final String BUILDING_PHASE = "EXPORT_PHASE_BUILDING";
    private static final String UPLOADING_PHASE = "EXPORT_PHASE_UPLOADING";
    private static final int PERCENT = 100;

    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private volatile int total;
    private volatile boolean uploading;

    void begin(int players, int punishmentsWithoutAccount) {
        total = players;
        skipped.set(punishmentsWithoutAccount);
    }

    void playerHandled() {
        handled.incrementAndGet();
    }

    void playerFailed() {
        failed.incrementAndGet();
    }

    void punishmentsSkipped(int count) {
        skipped.addAndGet(count);
    }

    void uploadStarts() {
        uploading = true;
    }

    boolean isUploading() {
        return uploading;
    }

    int handled() {
        return handled.get();
    }

    int failed() {
        return failed.get();
    }

    int skipped() {
        return skipped.get();
    }

    int total() {
        return total;
    }

    int percent() {
        int players = total;
        return players == 0 ? 0 : (int) ((long) handled.get() * PERCENT / players);
    }

    ExportProgressRequest toRequest() {
        return new ExportProgressRequest(uploading ? UPLOADING_PHASE : BUILDING_PHASE,
                handled(), total, failed(), skipped());
    }
}
