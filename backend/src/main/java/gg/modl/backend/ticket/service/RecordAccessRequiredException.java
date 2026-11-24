package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

@Getter
public class RecordAccessRequiredException extends BaseApplicationException {
    private final String recordId;
    @Nullable
    private final String emailHint;

    public RecordAccessRequiredException(String recordId, @Nullable String emailHint, String message) {
        super(message, HttpStatus.FORBIDDEN);
        this.recordId = recordId;
        this.emailHint = emailHint;
    }
}
