package gg.modl.backend.instance.setup;

public record SetupStatus(boolean claimed, SetupPhase phase, SetupHost host) {
}
