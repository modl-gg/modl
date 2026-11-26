package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

class ImmediateFeatureScheduler implements FeatureScheduler {
    private final FakeStaffModeOps ops;

    private Runnable timer;

    ImmediateFeatureScheduler(FakeStaffModeOps ops) {
        this.ops = ops;
    }

    void runTimerOnce() {
        if (timer != null) timer.run();
    }

    @Override
    public void runOnMainThread(Runnable task) {
        task.run();
    }

    @Override
    public void runForPlayer(UUID playerUuid, Runnable task) {
        if (!ops.isOnline(playerUuid)) return;
        task.run();
    }

    @Override
    public void runForPlayer(UUID playerUuid, Runnable task, Runnable gone) {
        if (ops.isOnline(playerUuid)) task.run();
        else gone.run();
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        task.run();
    }

    @Override
    public void runAsync(Runnable task) {
        task.run();
    }

    @Override
    public void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks) {
        if (!ops.isOnline(playerUuid)) return;
        task.run();
    }

    @Override
    public FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
        timer = task;
        return () -> timer = null;
    }

    @Override
    public void cancelTask(FeatureTask task) {
    }
}
