package gg.modl.backend.ticket.service;

import gg.modl.backend.database.GeneratedIdInsert;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketInserter {
    private final IdGenerator idGenerator;
    private final TicketAccessKeys ticketAccessKeys;
    private final TicketRepository ticketRepository;
    private final UnitOfWork unitOfWork;

    public Ticket insertNew(Server server, String prefix, Ticket ticket) {
        String accessKey = ticketAccessKeys.generate();
        return unitOfWork.write(() -> {
            ticket.setPublicAccessKey(accessKey);
            GeneratedIdInsert.insert(
                () -> prefix + "-" + idGenerator.nextSixDigitInt(),
                id -> {
                    ticket.setId(id);
                    return ticketRepository.insertIfAbsent(ticket);
                },
                GeneratedIdInsert.DEFAULT_ATTEMPTS);
            return ticket;
        });
    }
}
