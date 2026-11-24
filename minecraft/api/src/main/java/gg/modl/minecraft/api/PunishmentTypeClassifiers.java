package gg.modl.minecraft.api;

public final class PunishmentTypeClassifiers {

    private static volatile PunishmentTypeClassifier active = new RegistryPunishmentTypeClassifier();

    private PunishmentTypeClassifiers() {
    }

    public static synchronized void install(PunishmentTypeClassifier classifier) {
        active = classifier;
    }

    public static synchronized void uninstall(PunishmentTypeClassifier classifier) {
        if (active == classifier) active = new RegistryPunishmentTypeClassifier();
    }

    public static PunishmentTypeClassifier active() {
        return active;
    }
}
