package gg.modl.backend.instance.setup;

public record SetupClient(String key) {
    public static SetupClient identifiedBy(String networkKey) {
        return new SetupClient(networkKey);
    }
}
