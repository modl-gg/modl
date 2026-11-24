package gg.modl.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import gg.modl.backend.audit.repository.AdminTableBrowserRepository;
import gg.modl.backend.audit.repository.BrowsableTable;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AdminDatabaseBrowserServiceTest {
    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final AdminTableBrowserRepository repository = mock(AdminTableBrowserRepository.class);
    private final AdminDatabaseBrowserService service = new AdminDatabaseBrowserService(repository, new JsonColumns());

    @ParameterizedTest
    @ValueSource(strings = {"instance", "instance_secrets", "panel_sessions", "auth_codes", "webauthn_credentials", "staff",
        "TICKETS", "tickets; drop table players", "replays"})
    void onlyTheListedIdentifiersCanBeBrowsed(String identifier) {
        assertThatThrownBy(() -> service.getDatabaseTable(server, identifier, 10, 0)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void largeIdsSurviveTheStructWireFormatExactly() {
        when(repository.readPage(BrowsableTable.AUDIT_LOGS, 10, 0)).thenReturn(List.of(Map.of("id", 1_800_000_000_000_000_123L)));
        when(repository.count(BrowsableTable.AUDIT_LOGS)).thenReturn(1L);

        Struct row = ProtoMapperSupport.toStruct(service.getDatabaseTable(server, "logs", 10, 0).rows().getFirst());

        assertThat(row.getFieldsOrThrow("id").getStringValue()).isEqualTo("1800000000000000123");
    }
}
