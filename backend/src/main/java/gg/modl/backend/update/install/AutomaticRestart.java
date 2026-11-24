package gg.modl.backend.update.install;

final class AutomaticRestart implements InstanceRestart {
    private final Mode mode;
    private final Runnable request;

    AutomaticRestart(Mode mode, Runnable request) {
        this.mode = mode;
        this.request = request;
    }

    @Override
    public Mode mode() {
        return mode;
    }

    @Override
    public void restart() {
        request.run();
    }
}
