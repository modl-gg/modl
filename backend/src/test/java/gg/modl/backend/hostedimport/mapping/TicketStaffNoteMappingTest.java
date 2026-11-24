package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ExtendedJsonDecoder;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TicketStaffNoteMappingTest {
    @TempDir
    Path directory;

    @Test
    void aHostedStaffNoteReplyBecomesATicketNoteAndLeavesTheThread() throws IOException {
        Ticket ticket = map("{\"_id\":\"SUPPORT-1\",\"type\":\"support\",\"created\":{\"$date\":\"2026-01-01T00:00:00Z\"},"
            + "\"replies\":["
            + "{\"_id\":\"r1\",\"name\":\"Player\",\"content\":\"my question\",\"type\":\"user\","
            + "\"created\":{\"$date\":\"2026-01-01T00:01:00Z\"}},"
            + "{\"_id\":\"r2\",\"name\":\"Mod\",\"content\":\"informant is Alex\",\"type\":\" Staff-Note \",\"staff\":true,"
            + "\"created\":{\"$date\":\"2026-01-01T00:03:00Z\"}}],"
            + "\"notes\":[{\"text\":\"older note\",\"issuerName\":\"Admin\",\"date\":{\"$date\":\"2026-01-01T00:02:00Z\"}}]}");

        assertThat(ticket.getReplies()).extracting(TicketReply::getContent).containsExactly("my question");
        assertThat(ticket.getNotes()).extracting(TicketNote::getText).containsExactly("older note", "informant is Alex");
        assertThat(ticket.getNotes().get(1).getIssuerName()).isEqualTo("Mod");
    }

    private Ticket map(String json) throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture(), directory);
        return new TicketMapper(ImportTestContexts.context(index), id -> "/replays/" + id, ticket -> "key")
            .map(document(json))
            .orElseThrow();
    }

    private static ArchiveDocument document(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ArchiveDocument.of(ExtendedJsonDecoder.decodeLine(bytes, bytes.length));
    }
}
