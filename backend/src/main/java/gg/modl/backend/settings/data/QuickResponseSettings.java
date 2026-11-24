package gg.modl.backend.settings.data;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuickResponseSettings {
    public static final int MAX_CATEGORIES = 100;
    private static final String PARDON_APPEAL_ACTION = "pardon";
    private static final String REDUCE_APPEAL_ACTION = "reduce";
    private static final int MAX_ACTIONS_PER_CATEGORY = 100;
    private static final int MAX_TICKET_TYPES = 50;
    private static final int NAME_MAX_LENGTH = 128;
    private static final int MESSAGE_MAX_LENGTH = 10_000;

    @Builder.Default
    private List<Category> categories = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Category {
        @Size(max = RequestValidationLimits.QUICK_RESPONSE_CATEGORY_ID_MAX_LENGTH)
        private String id;
        @Size(max = NAME_MAX_LENGTH)
        private String name;
        @Builder.Default
        @Size(max = MAX_TICKET_TYPES)
        private List<String> ticketTypes = new ArrayList<>();
        @Builder.Default
        @Size(max = MAX_ACTIONS_PER_CATEGORY)
        @Valid
        private List<Action> actions = new ArrayList<>();
        private @Nullable Integer order;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Action {
        @Size(max = RequestValidationLimits.QUICK_RESPONSE_ACTION_ID_MAX_LENGTH)
        private String id;
        @Size(max = NAME_MAX_LENGTH)
        private String name;
        @NotBlank
        @Size(max = MESSAGE_MAX_LENGTH)
        private String message;
        private @Nullable Integer order;
        private @Nullable Boolean closeTicket;
        private @Nullable Boolean showPunishment;
        @Size(max = RequestValidationLimits.QUICK_RESPONSE_APPEAL_ACTION_MAX_LENGTH)
        private @Nullable String appealAction;

        public boolean approvesAppeal() {
            return hasAppealAction(PARDON_APPEAL_ACTION);
        }

        public boolean reducesAppeal() {
            return hasAppealAction(REDUCE_APPEAL_ACTION);
        }

        private boolean hasAppealAction(String expected) {
            return appealAction != null && expected.equalsIgnoreCase(appealAction.trim());
        }
    }
}
