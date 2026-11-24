package gg.modl.backend.email;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.SettingsCache;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.settings.service.VersionedSettingsSupport;
import java.time.Clock;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class MailSettingsService implements EmailCapability {
    private static final String MANAGED_BY_CONFIG_MESSAGE =
        "Email delivery is set by MODL_SMTP_* or connection.yml (embedded.smtp) and cannot be changed from the panel";
    private static final SettingsCodec<StoredMailSettings> CODEC = SettingsCodec.of(StoredMailSettings.class, StoredMailSettings::empty);

    private final Optional<MailConfiguration> configured;
    private final InstanceContext instanceContext;
    private final MailTester mailTester;
    private final VersionedSettingsSupport<StoredMailSettings> support;
    private final UnitOfWork unitOfWork;
    private final SettingsCache<Optional<MailConfiguration>> storedCache;

    public MailSettingsService(MailProperties mailProperties, InstanceContext instanceContext, MailTester mailTester,
                               SettingsDocumentService settingsDocumentService, UnitOfWork unitOfWork, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.storedCache = new SettingsCache<>(clock);
        this.configured = mailProperties.configuration();
        this.instanceContext = instanceContext;
        this.mailTester = mailTester;
        this.support = VersionedSettingsSupport.of(settingsDocumentService, SettingsType.MAIL, CODEC::decode);
    }

    public Optional<MailConfiguration> active() {
        return configured.or(this::stored);
    }

    @Override
    public boolean isDeliveryEnabled() {
        return active().isPresent();
    }

    public MailSettingsView view() {
        if (configured.isPresent()) {
            return new MailSettingsView(MailSettingsSource.CONFIG, configured);
        }
        Optional<MailConfiguration> stored = stored();
        return new MailSettingsView(stored.isPresent() ? MailSettingsSource.PANEL : MailSettingsSource.NONE, stored);
    }

    public MailSettingsView save(MailSettingsInput input, Optional<String> password) {
        requireNotManagedByConfig();
        MailConfiguration candidate = withEffectivePassword(input, password);
        mailTester.verify(candidate).ifPresent(failure -> {
            throw new ValidationException(failure.detail(), "MAIL_" + failure.outcome().name());
        });
        unitOfWork.write(() -> {
            support.updateLatest(instanceContext.instance(), current -> CODEC.encode(StoredMailSettings.of(candidate)));
            unitOfWork.afterCommit(storedCache::invalidate);
        });
        return view();
    }

    public MailSettingsView delete() {
        requireNotManagedByConfig();
        unitOfWork.write(() -> {
            support.delete(instanceContext.instance());
            unitOfWork.afterCommit(storedCache::invalidate);
        });
        return view();
    }

    public MailTestResult sendTest(Optional<MailSettingsInput> input, Optional<String> password, String recipient) {
        MailConfiguration candidate = configured
            .or(() -> input.map(settings -> withEffectivePassword(settings, password)))
            .or(this::stored)
            .orElseThrow(() -> new ConflictException("Email delivery is not set up yet", "MAIL_NOT_CONFIGURED"));
        return mailTester.sendTest(candidate, recipient);
    }

    private MailConfiguration withEffectivePassword(MailSettingsInput input, Optional<String> password) {
        if (password.isPresent()) {
            return input.withPassword(password.get().isEmpty() ? null : password.get());
        }
        MailConfiguration withoutPassword = input.withPassword(null);
        String reusable = active()
            .filter(withoutPassword::mayReusePasswordOf)
            .map(MailConfiguration::password)
            .orElse(null);
        return withoutPassword.withPassword(reusable);
    }

    private Optional<MailConfiguration> stored() {
        return storedCache.get(() -> support.get(instanceContext.instance()).toConfiguration());
    }

    private void requireNotManagedByConfig() {
        if (configured.isPresent()) {
            throw new ConflictException(MANAGED_BY_CONFIG_MESSAGE, "MAIL_MANAGED_BY_CONFIG");
        }
    }

    record StoredMailSettings(
        @Nullable String host,
        @Nullable Integer port,
        @Nullable String username,
        @Nullable String password,
        @Nullable String from,
        @Nullable Boolean starttls
    ) {
        static StoredMailSettings empty() {
            return new StoredMailSettings(null, null, null, null, null, null);
        }

        static StoredMailSettings of(MailConfiguration configuration) {
            return new StoredMailSettings(configuration.host(), configuration.port(), configuration.username(),
                configuration.password(), configuration.from(), configuration.starttls());
        }

        Optional<MailConfiguration> toConfiguration() {
            if (host == null || host.isBlank() || port == null || from == null || from.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new MailConfiguration(host, port, username, password, from, !Boolean.FALSE.equals(starttls)));
        }

        @Override
        public String toString() {
            return "StoredMailSettings[host=" + host + ", port=" + port + ", username=" + username + "]";
        }
    }
}
