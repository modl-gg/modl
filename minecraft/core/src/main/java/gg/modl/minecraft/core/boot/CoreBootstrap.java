package gg.modl.minecraft.core.boot;

import java.util.function.Consumer;

public interface CoreBootstrap {
    void prepare(CoreAvailability availability);

    void connect(BackendSession session);

    void stop();

    static CoreBootstrap of(Consumer<CoreAvailability> prepare, Consumer<BackendSession> connect, Runnable stop) {
        return new CoreBootstrap() {
            @Override
            public void prepare(CoreAvailability availability) {
                prepare.accept(availability);
            }

            @Override
            public void connect(BackendSession session) {
                connect.accept(session);
            }

            @Override
            public void stop() {
                stop.run();
            }
        };
    }
}
