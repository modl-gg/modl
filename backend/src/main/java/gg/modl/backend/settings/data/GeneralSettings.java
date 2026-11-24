package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneralSettings {
    private String serverDisplayName;
    private String homepageIconUrl;
    private String panelIconUrl;
    private String defaultLanguage;
    private String publicUrl;
    private boolean publicUrlManagedByConfig;
    private boolean serverNameManaged;
}
