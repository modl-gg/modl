package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MinecraftTicketServiceTimestampTest {

    private static final Instant REPORT_TIME = Instant.parse("2026-07-08T05:22:40Z");

    @Test
    void reconstructsSameDayUtcTime() {
        Instant result = MinecraftTicketService.reconstructTimestamp("5:22:37", REPORT_TIME);

        assertEquals(Instant.parse("2026-07-08T05:22:37Z"), result);
    }

    @Test
    void rollsBackToPreviousUtcDayWhenTimeIsAfterReport() {
        Instant result = MinecraftTicketService.reconstructTimestamp("23:59:50", REPORT_TIME);

        assertEquals(Instant.parse("2026-07-07T23:59:50Z"), result);
    }

    @Test
    void fallsBackToReportTimeWhenUnparseable() {
        Instant result = MinecraftTicketService.reconstructTimestamp("not-a-time", REPORT_TIME);

        assertEquals(REPORT_TIME, result);
    }
}
