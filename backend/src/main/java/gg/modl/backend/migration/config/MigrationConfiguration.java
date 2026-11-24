package gg.modl.backend.migration.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "modl.migration")
@Validated
@Getter
@Setter
public class MigrationConfiguration {
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024;

    private String uploadDir = "uploads/migrations";
    @Min(value = 1, message = "MODL_IMPORT_MAX_UPLOAD_MB must be at least 1")
    private long maxUploadMb = 500;

    public long maxUploadBytes() {
        return Math.multiplyExact(maxUploadMb, BYTES_PER_MEBIBYTE);
    }
}
