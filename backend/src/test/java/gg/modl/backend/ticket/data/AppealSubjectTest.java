package gg.modl.backend.ticket.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppealSubjectTest {

    @Test
    void readsTheSubjectThatAppealsAndImportedRecordsStore() {
        Map<String, Object> data = Map.of("punishmentId", "ban-1", "playerUuid", "0F8FAD5B-D9CB-469F-A165-70867728950E");

        assertThat(AppealSubject.from(data))
            .contains(new AppealSubject("ban-1", "0f8fad5b-d9cb-469f-a165-70867728950e"));
        assertThat(AppealSubject.punishmentIdOf(data)).contains("ban-1");
    }

    @Test
    void incompleteOrMistypedDataHasNoSubject() {
        assertThat(AppealSubject.from(null)).isEmpty();
        assertThat(AppealSubject.from(Map.of("punishmentId", "ban-1"))).isEmpty();
        assertThat(AppealSubject.from(Map.of("punishmentId", 42, "playerUuid", "uuid"))).isEmpty();
        assertThat(AppealSubject.punishmentIdOf(Map.of("punishmentId", " "))).isEmpty();
    }

    @Test
    void writesBothKeys() {
        Map<String, Object> data = new HashMap<>();

        new AppealSubject("ban-1", "uuid").writeTo(data);

        assertThat(data).containsOnly(Map.entry("punishmentId", "ban-1"), Map.entry("playerUuid", "uuid"));
    }
}
