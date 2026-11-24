package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppealFormField {
    private String id;
    private String type;
    private String label;
    private String description;
    private @Nullable Boolean required;
    private @Nullable Integer order;
    private String sectionId;
    @Builder.Default
    private List<String> options = new ArrayList<>();
    @Builder.Default
    private Map<String, String> optionSectionMapping = new HashMap<>();
}
