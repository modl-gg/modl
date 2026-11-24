package gg.modl.minecraft.features.reporter;

@FunctionalInterface
public interface TicketCreator {
    void createTicket(TicketRequest request);
}
