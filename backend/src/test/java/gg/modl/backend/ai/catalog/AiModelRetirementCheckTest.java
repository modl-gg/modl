package gg.modl.backend.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import gg.modl.backend.ai.model.AiModelNotice;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiModelRetirementCheckTest {
    private static final AiConnection CONNECTION = AiModelCatalogServiceTest.connection("key");
    private static final Instant RETIRES = Instant.parse("2027-02-01T00:00:00Z");

    @Test
    void aDeprecatedModelIsReported() {
        assertThat(notice(catalog(true, model("m1", true, null), model("m2", false, null))).warning())
            .isEqualTo(AiModelNotice.Warning.DEPRECATED);
    }

    @Test
    void aRetirementDateIsReported() {
        AiModelNotice notice = notice(catalog(true, model("m1", false, RETIRES)));

        assertThat(notice.warning()).isEqualTo(AiModelNotice.Warning.RETIRING);
        assertThat(notice.retiresAt()).isEqualTo(RETIRES);
    }

    @Test
    void aMissingModelIsReportedOnlyFromACompleteNonEmptyList() {
        assertThat(notice(catalog(true, model("other", false, null))).warning()).isEqualTo(AiModelNotice.Warning.NOT_LISTED);
        assertThat(notice(catalog(false, model("other", false, null)))).isNull();
        assertThat(notice(catalog(true))).isNull();
    }

    @Test
    void aHealthyModelHasNoNotice() {
        assertThat(notice(catalog(true, model("m1", false, null)))).isNull();
    }

    private static AiModelNotice notice(AiModelCatalog catalog) {
        return AiModelRetirementCheck.noticeFor(CONNECTION, catalog);
    }

    private static AiModelCatalog catalog(boolean complete, AiModelInfo... models) {
        return new AiModelCatalog(List.of(models), false, Instant.EPOCH, (AiFailureKind) null, complete);
    }

    private static AiModelInfo model(String id, boolean deprecated, Instant retiresAt) {
        return new AiModelInfo(id, null, true, deprecated, retiresAt, null, null);
    }
}
