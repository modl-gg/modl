package gg.modl.backend.iplookup.settings;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.util.Arrays;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlayerAddressSettingsService {
    public static final String PROVIDER_UNKNOWN = "IP_LOOKUP_PROVIDER_UNKNOWN";
    public static final String KEY_INVALID = "IP_LOOKUP_KEY_INVALID";
    public static final String KEY_MISSING = "IP_LOOKUP_KEY_MISSING";
    public static final String LIMIT_INVALID = "SHARED_ACCOUNT_LIMIT_INVALID";
    public static final String KEY_NOT_USED = "IP_LOOKUP_KEY_NOT_USED";

    private static final Pattern API_KEY = Pattern.compile("[\\x21-\\x7E]{1,256}");

    private final PlayerAddressSettingsStore store;
    private final PanelActionAuditor auditor;
    private final Clock clock;

    public PlayerAddressSettingsView view() {
        return viewOf(store.current());
    }

    public PlayerAddressSettingsView selectProvider(Server server, String actorEmail, @Nullable String providerId) {
        IpLookupProvider selected = providerId == null || providerId.isBlank() ? null : provider(providerId);
        PlayerAddressSettings saved = store.update(current -> {
            if (selected != null && !current.canUse(selected)) {
                throw new ValidationException("Save a key for this provider first", KEY_MISSING);
            }
            return current.withProvider(selected).withChoiceMade(clock.instant());
        });
        auditor.recordStaffAction(server, actorEmail, selected == null
            ? "Turned player IP lookups off"
            : "Chose " + selected.id() + " for player IP lookups");
        return viewOf(saved);
    }

    public PlayerAddressSettingsView saveKey(Server server, String actorEmail, String providerId, @Nullable String key) {
        IpLookupProvider target = keyedProvider(providerId);
        String validKey = requireValidKey(key);
        PlayerAddressSettings saved = store.update(current -> current.withKey(target, validKey));
        auditor.recordStaffAction(server, actorEmail, "Saved the " + target.id() + " IP lookup key");
        return viewOf(saved);
    }

    public PlayerAddressSettingsView deleteKey(Server server, String actorEmail, String providerId) {
        IpLookupProvider target = keyedProvider(providerId);
        PlayerAddressSettings saved = store.update(current -> current.withoutKey(target));
        auditor.recordStaffAction(server, actorEmail, "Removed the " + target.id() + " IP lookup key");
        return viewOf(saved);
    }

    public PlayerAddressSettingsView setSharedAccountLimit(Server server, String actorEmail, int limit) {
        if (!PlayerAddressSettings.validSharedAccountLimit(limit)) {
            throw new ValidationException("The limit must be between " + PlayerAddressSettings.MIN_SHARED_ACCOUNT_LIMIT
                + " and " + PlayerAddressSettings.MAX_SHARED_ACCOUNT_LIMIT, LIMIT_INVALID);
        }
        int previous = store.current().effectiveSharedAccountLimit();
        PlayerAddressSettings saved = store.update(current -> current.withSharedAccountLimit(limit));
        auditor.recordStaffAction(server, actorEmail,
            "Changed the shared-address linking limit from " + previous + " to " + limit + " accounts");
        return viewOf(saved);
    }

    public PlayerAddressSettingsView dismissNotice() {
        store.updateNotice(current -> current.detected() ? current.dismissedAt(clock.instant()) : current);
        return view();
    }

    public PlayerAddressSettingsView dismissChoicePrompt() {
        return viewOf(store.update(current -> current.choicePending() ? current.withChoiceMade(clock.instant()) : current));
    }

    public IpLookupAccess testAccess(String providerId, @Nullable String draftKey) {
        IpLookupProvider target = provider(providerId);
        if (draftKey == null) {
            return store.current().access(target)
                .orElseThrow(() -> new ValidationException("Save a key for this provider first", KEY_MISSING));
        }
        if (!target.needsKey()) {
            throw new ValidationException("This provider is used without a key", KEY_NOT_USED);
        }
        return IpLookupAccess.keyed(target, requireValidKey(draftKey));
    }

    private PlayerAddressSettingsView viewOf(PlayerAddressSettings settings) {
        return new PlayerAddressSettingsView(
            settings.selectedProvider().orElse(null),
            Arrays.stream(IpLookupProvider.values()).filter(settings::hasKey).toList(),
            settings.activeLookup().isPresent(),
            settings.choicePending(),
            settings.effectiveSharedAccountLimit(),
            store.notice());
    }

    private static String requireValidKey(@Nullable String key) {
        if (key == null || !API_KEY.matcher(key).matches()) {
            throw new ValidationException("The key must be 1 to 256 printable characters without spaces", KEY_INVALID);
        }
        return key;
    }

    private static IpLookupProvider keyedProvider(String providerId) {
        IpLookupProvider target = provider(providerId);
        if (!target.needsKey()) {
            throw new ValidationException("This provider is used without a key", KEY_NOT_USED);
        }
        return target;
    }

    private static IpLookupProvider provider(String providerId) {
        return IpLookupProvider.ofId(providerId)
            .orElseThrow(() -> new ValidationException("Unknown IP lookup provider", PROVIDER_UNKNOWN));
    }
}
