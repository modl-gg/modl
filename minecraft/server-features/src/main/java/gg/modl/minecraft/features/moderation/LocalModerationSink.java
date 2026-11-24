package gg.modl.minecraft.features.moderation;

import gg.modl.minecraft.core.network.ModerationPublisher;
import gg.modl.minecraft.core.network.ModerationSnapshot;
import gg.modl.minecraft.core.network.ModerationStateSink;

public final class LocalModerationSink implements ModerationStateSink {
    public static final String SOURCE_ID = "local";

    private final ModerationReconciler reconciler;
    private final ModerationPublisher publisher;

    private LocalModerationSink(ModerationReconciler reconciler, ModerationPublisher publisher) {
        this.reconciler = reconciler;
        this.publisher = publisher;
    }

    public static LocalModerationSink attach(ModerationReconciler reconciler, ModerationPublisher publisher) {
        LocalModerationSink sink = new LocalModerationSink(reconciler, publisher);
        reconciler.linkUp();
        publisher.addSink(sink);
        return sink;
    }

    @Override
    public void publish(ModerationSnapshot snapshot) {
        reconciler.accept(SOURCE_ID, snapshot);
    }

    public void detach() {
        publisher.removeSink(this);
        reconciler.linkDown();
    }
}
