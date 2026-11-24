package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.features.reporter.TicketCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

import static gg.modl.minecraft.core.util.Java8Collections.listOf;

public final class TicketCreatorFactory {

    private TicketCreatorFactory() {}

    public static TicketCreator standalone(JavaPlugin plugin, Supplier<ModlHttpClient> httpClientSupplier) {
        return report -> {
            CreateTicketRequest request = CreateTicketRequest.builder()
                    .creatorUuid(report.getCreatorUuid())
                    .type(report.getType())
                    .creatorName(report.getCreatorName())
                    .subject(report.getSubject())
                    .description(report.getDescription())
                    .reportedPlayerUuid(report.getReportedPlayerUuid())
                    .reportedPlayerName(report.getReportedPlayerName())
                    .priority(report.getPriority())
                    .createdServer(report.getCreatedServer())
                    .tags(listOf())
                    .replayUrl(report.getReplayUrl())
                    .build();
            httpClientSupplier.get().createTicket(request).thenAccept(response -> {
                if (response.isSuccess()) {
                    plugin.getLogger().info("[anticheat] Report ticket created: " + response.getTicketId());
                } else {
                    plugin.getLogger().warning("[anticheat] Failed to create report ticket: " + response.getMessage());
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("[anticheat] Error creating report ticket: " + throwable.getMessage());
                return null;
            });
        };
    }
}
