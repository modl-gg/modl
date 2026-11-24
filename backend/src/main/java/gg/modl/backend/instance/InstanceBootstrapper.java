package gg.modl.backend.instance;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.repository.InstanceRepository;
import gg.modl.backend.instance.setup.SetupCodeService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerProvisioningService;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.backend.staff.service.StaffService;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InstanceBootstrapper implements SmartInitializingSingleton {
    private final InstanceProperties instanceProperties;
    private final UnitOfWork unitOfWork;
    private final InstanceRepository instanceRepository;
    private final InstanceService instanceService;
    private final InstanceContext instanceContext;
    private final InstanceSecretsService instanceSecretsService;
    private final ServerProvisioningService provisioningService;
    private final ApiKeySettingsService apiKeySettingsService;
    private final SetupCodeService setupCodeService;
    private final StaffService staffService;
    private final PublicUrls publicUrls;
    private final OperatorNotice operatorNotice;
    private final Clock clock;

    @Override
    public void afterSingletonsInstantiated() {
        requireValidConfiguredPublicUrl();
        requireUsableConfiguredApiKey();
        instanceSecretsService.ensureInitialized();

        Server server = unitOfWork.write(() ->
            instanceRepository.ensure(instanceProperties.initialServerName(), clock.instant()));
        server = applyConfiguredIdentity(server);
        provisioningService.provision(server);
        apiKeySettingsService.ensureInstanceKey(server);
        instanceContext.refresh();

        if (!setupCodeService.issueIfNeeded()) {
            setupCodeService.revoke();
            warnWhenPanelUrlUnknown();
        }
    }

    private Server applyConfiguredIdentity(Server server) {
        Optional<String> adminEmail = configuredAdminEmail();
        if (adminEmail.isPresent() && !adminEmail.get().equals(server.getAdminEmail())) {
            server = replaceOwner(adminEmail.get(), server.getAdminEmail());
        }
        Optional<String> serverName = instanceProperties.configuredServerName();
        if (serverName.isPresent() && !serverName.get().equals(server.getServerName())) {
            server = instanceService.changeServerName(serverName.get());
        }
        return server;
    }

    private Server replaceOwner(String adminEmail, @Nullable String previousAdminEmail) {
        return unitOfWork.write(() -> {
            Server reassigned = instanceService.assignConfiguredAdminEmail(adminEmail);
            staffService.offboardPreviousAdminEmail(reassigned, previousAdminEmail);
            return reassigned;
        });
    }

    private void warnWhenPanelUrlUnknown() {
        if (publicUrls.knownOrigin().isEmpty()) {
            operatorNotice.publish("The Panel URL is not set, so in-game and email links point to {}. Log in to the panel"
                + " from its public address or set it in " + PanelLocations.PANEL_SETTINGS + ".", publicUrls.origin());
        }
    }

    private Optional<String> configuredAdminEmail() {
        return instanceProperties.configuredAdminEmail().map(configured -> {
            String normalized = EmailAddressUtil.normalizeIfValid(configured);
            if (normalized == null) {
                throw new IllegalStateException("MODL_ADMIN_EMAIL is not a valid email address: " + configured);
            }
            return normalized;
        });
    }

    private void requireValidConfiguredPublicUrl() {
        instanceProperties.configuredPublicUrl().ifPresent(publicUrl -> {
            try {
                HttpOrigin.requireExact(publicUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("MODL_PUBLIC_URL '" + publicUrl + "' " + e.getMessage()
                    + "; use an http(s) origin such as https://panel.example.com");
            }
        });
    }

    private void requireUsableConfiguredApiKey() {
        instanceProperties.configuredApiKey().ifPresent(apiKey -> {
            if (apiKey.length() < InstanceProperties.MIN_API_KEY_LENGTH) {
                throw new IllegalStateException("MODL_API_KEY must be at least " + InstanceProperties.MIN_API_KEY_LENGTH
                    + " characters long; leave it empty to let modl generate one");
            }
            if (apiKey.codePointCount(0, apiKey.length()) > InstanceProperties.MAX_API_KEY_LENGTH) {
                throw new IllegalStateException("MODL_API_KEY must be at most " + InstanceProperties.MAX_API_KEY_LENGTH
                    + " characters long; leave it empty to let modl generate one");
            }
        });
    }
}
