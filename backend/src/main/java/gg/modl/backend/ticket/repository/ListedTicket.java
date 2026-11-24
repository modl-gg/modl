package gg.modl.backend.ticket.repository;

import gg.modl.backend.ticket.data.Ticket;

public record ListedTicket(Ticket ticket, ThreadDigest thread) {
}
