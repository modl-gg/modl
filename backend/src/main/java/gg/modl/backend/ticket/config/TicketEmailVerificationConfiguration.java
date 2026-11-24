package gg.modl.backend.ticket.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "modl.ticket.email-verification")
@Validated
@Getter
@Setter
public class TicketEmailVerificationConfiguration {
    private long codeExpirySeconds = 300;
    private long accessExpirySeconds = Duration.ofHours(24).toSeconds();
    private long resendCooldownSeconds = 60;
    private int failureLimit = 20;
    private long failureWindowSeconds = Duration.ofHours(24).toSeconds();
}
