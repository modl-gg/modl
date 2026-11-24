package gg.modl.backend.settings.controller;

import gg.modl.backend.email.MailConfiguration;
import gg.modl.backend.email.MailSettingsInput;
import gg.modl.backend.email.MailSettingsView;
import gg.modl.backend.email.MailTestResult;
import gg.modl.proto.modl.v1.MailSettings;
import gg.modl.proto.modl.v1.MailSettingsResponse;
import gg.modl.proto.modl.v1.MailSource;
import gg.modl.proto.modl.v1.MailTestOutcome;
import gg.modl.proto.modl.v1.TestMailResponse;

final class MailSettingsProtoMapper {
    private MailSettingsProtoMapper() {
    }

    static MailSettingsInput toInput(MailSettings settings) {
        return new MailSettingsInput(settings.getHost(), settings.getPort(), settings.getUsername(), settings.getFrom(),
            settings.getStarttls());
    }

    static MailSettingsResponse toResponse(MailSettingsView view) {
        MailSettingsResponse.Builder response = MailSettingsResponse.newBuilder()
            .setSource(switch (view.source()) {
                case NONE -> MailSource.MAIL_SOURCE_NONE;
                case CONFIG -> MailSource.MAIL_SOURCE_CONFIG;
                case PANEL -> MailSource.MAIL_SOURCE_PANEL;
            })
            .setPasswordSet(view.passwordSet());
        view.settings().map(MailSettingsProtoMapper::toProto).ifPresent(response::setSettings);
        return response.build();
    }

    static TestMailResponse toResponse(MailTestResult result) {
        return TestMailResponse.newBuilder()
            .setOutcome(switch (result.outcome()) {
                case DELIVERED -> MailTestOutcome.MAIL_TEST_OUTCOME_DELIVERED;
                case CONNECT_FAILED -> MailTestOutcome.MAIL_TEST_OUTCOME_CONNECT_FAILED;
                case TLS_FAILED -> MailTestOutcome.MAIL_TEST_OUTCOME_TLS_FAILED;
                case AUTH_FAILED -> MailTestOutcome.MAIL_TEST_OUTCOME_AUTH_FAILED;
                case REJECTED -> MailTestOutcome.MAIL_TEST_OUTCOME_REJECTED;
                case TIMED_OUT -> MailTestOutcome.MAIL_TEST_OUTCOME_TIMED_OUT;
            })
            .setDetail(result.detail())
            .setRecipient(result.recipient())
            .build();
    }

    private static MailSettings toProto(MailConfiguration configuration) {
        return MailSettings.newBuilder()
            .setHost(configuration.host())
            .setPort(configuration.port())
            .setUsername(configuration.username() == null ? "" : configuration.username())
            .setFrom(configuration.from())
            .setStarttls(configuration.starttls())
            .build();
    }
}
