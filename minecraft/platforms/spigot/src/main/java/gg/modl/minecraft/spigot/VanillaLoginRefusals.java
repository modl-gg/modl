package gg.modl.minecraft.spigot;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class VanillaLoginRefusals implements LoginRefusalPrediction {
    private static final long MAIN_THREAD_WAIT_MILLIS = 1000L;

    private final Consumer<Runnable> mainThread;

    VanillaLoginRefusals(Consumer<Runnable> mainThread) {
        this.mainThread = mainThread;
    }

    @Override
    public boolean refuses(UUID uuid, InetAddress address) {
        CompletableFuture<Boolean> prediction = new CompletableFuture<>();
        mainThread.accept(() -> prediction.complete(readListsOnMainThread(uuid, address)));
        try {
            return prediction.get(MAIN_THREAD_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException unavailable) {
            return false;
        }
    }

    private static boolean readListsOnMainThread(UUID uuid, InetAddress address) {
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            if (Bukkit.hasWhitelist() && !player.isWhitelisted() && !player.isOp()) return true;
            if (player.isBanned()) return true;
            return address != null && Bukkit.getIPBans().contains(address.getHostAddress());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
