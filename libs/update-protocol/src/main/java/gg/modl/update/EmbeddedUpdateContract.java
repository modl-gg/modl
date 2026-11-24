package gg.modl.update;

public final class EmbeddedUpdateContract {
    public static final int HOST_API = 1;
    public static final int LAUNCHER_API = 1;
    public static final int MIN_JAVA = 21;
    public static final String PREPARE = "updatePrepare";
    public static final String RESTART = "updateRestart";
    public static final String PHASE = "updatePhase";
    public static final String HOST = "updateHost";
    public static final String PANEL_RESTART = "panelRestart";
    public static final String LAUNCHER_ENVIRONMENT = "MODL_LAUNCHER";
    public static final int RESTART_FOR_UPDATE_EXIT_CODE = 75;
    public static final int RESTART_EXIT_CODE = 76;
    public static final String PHASE_BACKUP_HEARTBEAT = "BACKUP_HEARTBEAT";

    private EmbeddedUpdateContract() {
    }
}
