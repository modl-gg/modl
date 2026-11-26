package gg.modl.minecraft.replay.recording;

final class Fixed {
    static final double SCALE = 4096.0;

    private Fixed() {
    }

    static long of(double value) {
        return Math.round(value * SCALE);
    }

    static int delta(double value) {
        return (int) Math.round(value * SCALE);
    }

    static int blockOf(long fixed) {
        return (int) Math.floor(fixed / SCALE);
    }
}
