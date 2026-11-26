package gg.modl.minecraft.features;

import gg.modl.minecraft.core.Scheduler;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public interface FeatureScheduler extends Scheduler {

    void runForPlayer(UUID playerUuid, Runnable task);

    void runForPlayer(UUID playerUuid, Runnable task, Runnable gone);

    void runLater(Runnable task, long delayTicks);

    void runAsync(Runnable task);

    void runForPlayerLater(UUID playerUuid, Runnable task, long delayTicks);

    FeatureTask runTimerAsync(Runnable task, long delay, long period, TimeUnit unit);

    void cancelTask(FeatureTask task);
}
