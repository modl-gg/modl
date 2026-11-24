package gg.modl.backend.update.install;

public interface InstanceRestart {
    InstanceRestart MANUAL = new InstanceRestart() {
        @Override
        public Mode mode() {
            return Mode.MANUAL;
        }

        @Override
        public void restart() {
            throw new IllegalStateException("This installation is restarted by its operator");
        }
    };

    enum Mode {
        IN_PLACE,
        EXIT,
        MANUAL
    }

    Mode mode();

    void restart();
}
