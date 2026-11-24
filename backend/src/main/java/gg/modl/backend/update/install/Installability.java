package gg.modl.backend.update.install;

public enum Installability {
    AVAILABLE,
    DOCKER,
    UNMANAGED,
    PLUGIN_JAR_REQUIRED,
    UPGRADE_PATH_UNSUPPORTED,
    JAVA_TOO_OLD,
    LAUNCHER_TOO_OLD,
    DATA_DIRECTORY_UNSUPPORTED
}
