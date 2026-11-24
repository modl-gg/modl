package gg.modl.backend.settings.data;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import gg.modl.proto.modl.v1.AppealForm;
import gg.modl.proto.modl.v1.OffenseLevelDurations;
import gg.modl.proto.modl.v1.PunishmentDurations;
import gg.modl.proto.modl.v1.PunishmentTypeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurationDetailBoundsTest {
    private static final List<String> PANEL_UNITS = List.of("seconds", "minutes", "hours", "days", "weeks", "months");
    private static final List<String> PANEL_KINDS = List.of("mute", "ban", "permanent mute", "permanent ban");

    private final Validator validator = ValidatorFactory.newBuilder().build();

    @Test
    void everyUnitAndKindThePanelSendsIsAcceptedAndUnderstood() throws ValidationException {
        for (String unit : PANEL_UNITS) {
            for (String kind : PANEL_KINDS) {
                assertThat(validates(detail(1, unit, kind))).as(unit + " " + kind).isTrue();
                assertThat(validates(singleSeverity(detail(1, unit, kind)))).as(unit + " " + kind).isTrue();
                DurationDetail domain = new DurationDetail(1, unit, kind);
                assertThat(domain.isPermanent() || domain.toMilliseconds() > 0).as(unit + " " + kind).isTrue();
            }
        }
        assertThat(validates(detail(0, "", ""))).isTrue();
    }

    @Test
    void unboundedOrUnknownDurationTextIsRefusedAtTheBoundary() throws ValidationException {
        assertThat(validates(detail(1, "d".repeat(10_000), "ban"))).isFalse();
        assertThat(validates(detail(1, "fortnights", "ban"))).isFalse();
        assertThat(validates(detail(1, "days", "b".repeat(10_000)))).isFalse();
        assertThat(validates(detail(1, "days", "exile"))).isFalse();
        assertThat(validates(singleSeverity(detail(1, "days", "exile")))).isFalse();
        assertThat(validates(detail(-1, "days", "ban"))).isFalse();
        assertThat(validates(detail(100_000_001, "days", "ban"))).isFalse();
    }

    private boolean validates(gg.modl.proto.modl.v1.DurationDetail detail) throws ValidationException {
        OffenseLevelDurations levels = OffenseLevelDurations.newBuilder().setFirst(detail).build();
        return validator.validate(request().setDurations(PunishmentDurations.newBuilder().setLow(levels)).build()).isSuccess();
    }

    private boolean validates(OffenseLevelDurations singleSeverity) throws ValidationException {
        return validator.validate(request().setSingleSeverityDurations(singleSeverity).build()).isSuccess();
    }

    private static OffenseLevelDurations singleSeverity(gg.modl.proto.modl.v1.DurationDetail detail) {
        return OffenseLevelDurations.newBuilder().setFirst(detail).build();
    }

    private static PunishmentTypeRequest.Builder request() {
        return PunishmentTypeRequest.newBuilder().setName("Chat Abuse").setCategory("Social")
            .setAppealForm(AppealForm.newBuilder().build());
    }

    private static gg.modl.proto.modl.v1.DurationDetail detail(int value, String unit, String type) {
        return gg.modl.proto.modl.v1.DurationDetail.newBuilder().setValue(value).setUnit(unit).setType(type).build();
    }
}
