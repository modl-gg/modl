package gg.modl.backend.update.launcher;

import gg.modl.update.EmbeddedUpdateContract;
import org.springframework.context.ConfigurableApplicationContext;

public final class UpdateRestart {
    private UpdateRestart() {
    }

    public static void restartJarForUpdate(ConfigurableApplicationContext context) {
        restart(context, EmbeddedUpdateContract.RESTART_FOR_UPDATE_EXIT_CODE, "modl-update-restart");
    }

    public static void restartJar(ConfigurableApplicationContext context) {
        restart(context, EmbeddedUpdateContract.RESTART_EXIT_CODE, "modl-restart");
    }

    public static void exitToBeRestarted(ConfigurableApplicationContext context) {
        Thread exit = new Thread(() -> {
            context.close();
            System.exit(EmbeddedUpdateContract.RESTART_EXIT_CODE);
        }, "modl-exit-restart");
        exit.setDaemon(false);
        exit.start();
    }

    private static void restart(ConfigurableApplicationContext context, int childExitCode, String threadName) {
        Thread restart = new Thread(() -> {
            context.close();
            if (UpdateLauncher.isChild()) {
                System.exit(childExitCode);
                return;
            }
            System.gc();
            System.exit(UpdateLauncher.superviseAfterInProcessServer());
        }, threadName);
        restart.setDaemon(false);
        restart.start();
    }
}
