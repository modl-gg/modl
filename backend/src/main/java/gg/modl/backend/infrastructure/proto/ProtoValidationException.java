package gg.modl.backend.infrastructure.proto;

import build.buf.protovalidate.ValidationResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.stream.Collectors;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ProtoValidationException extends RuntimeException {

    private final String message;
    private final List<ProtoFieldViolation> violations;

    public ProtoValidationException(ValidationResult result) {
        this(result.toProto().getViolationsList().stream().map(ProtoFieldViolation::from).toList());
    }

    private ProtoValidationException(List<ProtoFieldViolation> violations) {
        this(formatViolations(violations), violations);
    }

    private ProtoValidationException(String message, List<ProtoFieldViolation> violations) {
        super(message);
        this.message = message;
        this.violations = violations;
    }

    public String userMessage() {
        return message;
    }

    public List<ProtoFieldViolation> getViolations() {
        return violations;
    }

    private static String formatViolations(List<ProtoFieldViolation> violations) {
        return violations.stream()
                .map(violation -> violation.fieldPath() + ": " + violation.message())
                .collect(Collectors.joining(", "));
    }
}
