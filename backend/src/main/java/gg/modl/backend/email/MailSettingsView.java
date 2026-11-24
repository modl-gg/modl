package gg.modl.backend.email;

import java.util.Optional;

public record MailSettingsView(MailSettingsSource source, Optional<MailConfiguration> settings) {

    public boolean passwordSet() {
        return settings.map(MailConfiguration::hasPassword).orElse(false);
    }
}
