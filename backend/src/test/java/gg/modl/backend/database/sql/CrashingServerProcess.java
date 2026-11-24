package gg.modl.backend.database.sql;

import gg.modl.backend.BackendApplication;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

public final class CrashingServerProcess {
    static final String LISTENING = "crash server listening on ";

    private CrashingServerProcess() {
    }

    public static void main(String[] args) throws InterruptedException {
        CrashingEngineProcess.haltWhenParentIsGone();
        ConfigurableApplicationContext context = BackendApplication.application(Map.of(
            "modl.data-dir", args[0],
            "modl.database.url", "",
            "modl.instance.api-key", args[1],
            "modl.storage.type", "local",
            "server.address", "127.0.0.1",
            "server.port", "0"
        )).run();
        System.out.println(LISTENING + context.getEnvironment().getRequiredProperty("local.server.port"));
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
