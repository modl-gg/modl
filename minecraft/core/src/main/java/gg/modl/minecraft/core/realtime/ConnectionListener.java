package gg.modl.minecraft.core.realtime;

public interface ConnectionListener {
    void onConnected(boolean first);

    void onDisconnected();

    void onResyncAdvised();

    void onReplaced();

    void onTerminated(TerminationReason reason);
}
