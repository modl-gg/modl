package gg.modl.backend.update.compat;

import gg.modl.backend.infrastructure.exception.ErrorResponseDTO;
import gg.modl.update.CompatibilityHeaders;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CompatibilityRefusalAdvice {
    @ExceptionHandler(VersionMismatchException.class)
    public ResponseEntity<ErrorResponseDTO> refuse(VersionMismatchException refusal) {
        return ResponseEntity.status(refusal.getStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .header(CompatibilityHeaders.COMPATIBILITY, refusal.getCompatibility().name())
            .header(CompatibilityHeaders.SERVER_VERSION, refusal.getServerVersion())
            .body(ErrorResponseDTO.of(refusal.getStatus().value(), refusal.userMessage(), refusal.getMachineCode()));
    }
}
