package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.STAFF;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.settings.data.SupportedLanguages;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.staff.data.Staff;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

public final class StaffRecords {
    private static final SettingsCodec<Staff.TicketSubscriptionSettings> SUBSCRIPTION_SETTINGS =
        SettingsCodec.of(Staff.TicketSubscriptionSettings.class, Staff.TicketSubscriptionSettings::new);

    private StaffRecords() {
    }

    public static Staff toStaff(StaffPlan.Candidate candidate, ImportFit fit, Instant fallback) {
        ArchiveDocument source = candidate.source();
        Instant created = Objects.requireNonNullElse(source.instant("createdAt"), fallback);
        String language = source.string("language");
        String dateFormat = fit.columnIfPresent(STAFF.DATE_FORMAT, source.text("dateFormat"));
        return Staff.builder()
            .id(candidate.targetId())
            .email(candidate.email())
            .username(fit.column(STAFF.USERNAME, candidate.username().trim()))
            .roleId(candidate.roleId())
            .language(language != null && SupportedLanguages.isSupported(language) ? language : SupportedLanguages.DEFAULT)
            .dateFormat(dateFormat == null || dateFormat.isBlank() ? Staff.DEFAULT_DATE_FORMAT : dateFormat)
            .ticketSubscriptionSettings(subscriptionSettings(source).orElse(null))
            .lastSeen(source.instant("lastSeen"))
            .createdAt(created)
            .updatedAt(Objects.requireNonNullElse(source.instant("updatedAt"), created))
            .build();
    }

    public static Optional<Staff.TicketSubscriptionSettings> subscriptionSettings(ArchiveDocument source) {
        return source.document("ticketSubscriptionSettings").flatMap(StaffRecords::decoded);
    }

    private static Optional<Staff.TicketSubscriptionSettings> decoded(ArchiveDocument settings) {
        try {
            return Optional.of(SUBSCRIPTION_SETTINGS.decodeStrict(SettingsTrees.withIds(settings.fields())));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isActiveSubscription(ArchiveDocument subscription) {
        return !Boolean.FALSE.equals(subscription.boolOrNull("active"));
    }

    @Nullable
    public static String subscribedTicket(ArchiveDocument subscription) {
        return subscription.idOf("ticketId");
    }
}
