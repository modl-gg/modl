package gg.modl.backend.infrastructure.exception;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.database.UnstorableTextException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.infrastructure.proto.ProtoValidationException;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.ticket.controller.PublicVerificationProtoMapper;
import gg.modl.backend.ticket.service.RecordAccessRequiredException;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.FieldViolation;
import gg.modl.proto.modl.v1.RetryMetadata;
import gg.modl.proto.modl.v1.TicketVerificationRequiredResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private static final String INVALID_DATA_MESSAGE = "Invalid data provided.";
    private static final String CONFLICT_MESSAGE = "The request conflicts with an existing record.";
    private static final String DATABASE_BUSY_CODE = "DATABASE_BUSY";
    private static final long MILLIS_PER_SECOND = 1000;
    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;

    @ExceptionHandler(RecordAccessRequiredException.class)
    public ResponseEntity<TicketVerificationRequiredResponse> handleRecordAccessRequired(RecordAccessRequiredException ex) {
        return ResponseEntity.status(ex.getStatus()).body(PublicVerificationProtoMapper.toVerificationRequiredResponse(
            ex.getRecordId(), ex.getEmailHint(), ex.userMessage()));
    }

    @ExceptionHandler(RetryableApplicationException.class)
    public ResponseEntity<?> handleRetryable(RetryableApplicationException ex, HttpServletRequest request) {
        Optional<Duration> retryAfter = ex.retryAfter();
        if (retryAfter.isEmpty()) {
            return handleApplicationException(ex, request);
        }
        return respondRetryingAfter(request, ex.getStatus(), ex.getMachineCode(), ex.userMessage(), retryAfterSeconds(retryAfter.get()));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<?> handleApplicationException(BaseApplicationException ex, HttpServletRequest request) {
        return respond(request, ex.getStatus(), ex.getMachineCode(), ex.userMessage());
    }

    @ExceptionHandler({DuplicateRowException.class, ReferenceViolationException.class})
    public ResponseEntity<?> handleRowConflict(RuntimeException ex, HttpServletRequest request) {
        log.debug("Row conflict for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(request, HttpStatus.CONFLICT, "CONFLICT", CONFLICT_MESSAGE);
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<?> handleConcurrentUpdate(ConcurrentUpdateException ex, HttpServletRequest request) {
        log.debug("Concurrent update for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(request, HttpStatus.CONFLICT, "CONFLICT", "The record was changed by another request. Try again.");
    }

    @ExceptionHandler(StaleClientVersionException.class)
    public ResponseEntity<?> handleStaleClientVersion(StaleClientVersionException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(HttpStatus.CONFLICT, "CONFLICT", ex.userMessage());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "status", HttpStatus.CONFLICT.value(),
            "error", ex.userMessage(),
            "message", ex.userMessage(),
            "code", "CONFLICT",
            "currentVersion", ex.currentVersion()
        ));
    }

    @ExceptionHandler(ValueTooLongException.class)
    public ResponseEntity<?> handleValueTooLong(ValueTooLongException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.userMessage());
    }

    @ExceptionHandler(UnstorableTextException.class)
    public ResponseEntity<?> handleUnstorableText(UnstorableTextException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.userMessage());
    }

    @ExceptionHandler(DatabaseBusyException.class)
    public ResponseEntity<?> handleDatabaseBusy(DatabaseBusyException ex, HttpServletRequest request) {
        log.debug("Database busy for {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return respondRetryingAfter(request, HttpStatus.SERVICE_UNAVAILABLE, DATABASE_BUSY_CODE,
            "The server is busy. Try again shortly.", retryAfterSeconds(ex.retryAfter()));
    }

    @ExceptionHandler(DatabaseUnavailableException.class)
    public ResponseEntity<?> handleDatabaseUnavailable(DatabaseUnavailableException ex, HttpServletRequest request) {
        Optional<Duration> retryAfter = ex.reason().retryAfter();
        if (retryAfter.isPresent()) {
            return respondRetryingAfter(request, HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.userMessage(),
                retryAfterSeconds(retryAfter.get()));
        }
        return respond(request, HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.userMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Unhandled IllegalArgumentException. Consider replacing with a typed exception at the throw site", ex);
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid argument");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParam(
        MissingServletRequestParameterException ex,
        HttpServletRequest request
    ) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid value for parameter: " + ex.getName());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", fieldErrorMessage(ex));
    }

    private static String fieldErrorMessage(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
        return details.isEmpty() ? INVALID_DATA_MESSAGE : "Invalid data provided: " + details + ".";
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<?> handleHandlerMethodValidation(
        HandlerMethodValidationException ex,
        HttpServletRequest request
    ) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_DATA_MESSAGE);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<?> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Not acceptable");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException ex,
        HttpServletRequest request
    ) {
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported: " + ex.getMethod());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<?> handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint found for " + ex.getHttpMethod() + " " + ex.getRequestURL());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("No resource found for {} {}", request.getMethod(), ex.getResourcePath());
        }
        return respond(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint found for " + request.getMethod() + " " + request.getRequestURI());
    }

    @ExceptionHandler(ProtoValidationException.class)
    public ResponseEntity<?> handleProtoValidation(ProtoValidationException ex, HttpServletRequest request) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            ApiError.Builder error = ApiError.newBuilder()
                .setStatusCode(400)
                .setCode("INVALID_ARGUMENT")
                .setMessage(ex.userMessage());
            ex.getViolations().stream()
                .map(violation -> FieldViolation.newBuilder()
                    .setField(violation.fieldPath())
                    .setMessage(violation.message())
                    .build())
                .forEach(error::addFieldViolations);
            return protobufError(HttpStatus.BAD_REQUEST, error.build());
        }
        return ResponseEntity.badRequest().body(ErrorResponseDTO.of(400, INVALID_DATA_MESSAGE, "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            if (status.is4xxClientError()) {
                log.debug("Client error {} for {} {}", status.value(), request.getMethod(), request.getRequestURI());
            } else {
                log.error("Server error from framework exception", ex);
            }
            String code = HttpErrorMapping.machineCode(status);
            String message = status.is4xxClientError() ? status.getReasonPhrase() : "An internal error occurred";
            return respond(request, status, code, message);
        }

        log.error("Unhandled exception", ex);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "An internal error occurred");
    }

    private ResponseEntity<?> respond(HttpServletRequest request, HttpStatus status, String code, String message) {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return protobufError(status, code, message);
        }
        return ResponseEntity.status(status).body(ErrorResponseDTO.of(status.value(), message, code));
    }

    private ResponseEntity<?> respondRetryingAfter(HttpServletRequest request, HttpStatus status, String code, String message,
                                                   long seconds) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            return response.contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF).body(apiError(status, code, message)
                .setRetryMetadata(RetryMetadata.newBuilder().setRetryAfterSeconds(seconds).setRetryAfterMs(seconds * MILLIS_PER_SECOND))
                .build());
        }
        return response.body(ErrorResponseDTO.of(status.value(), message, code).retryingAfter(seconds));
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND);
    }

    private ResponseEntity<ApiError> protobufError(HttpStatus status, String code, String message) {
        return protobufError(status, apiError(status, code, message).build());
    }

    private static ApiError.Builder apiError(HttpStatus status, String code, String message) {
        return ApiError.newBuilder()
            .setStatusCode(status.value())
            .setCode(code)
            .setMessage(message);
    }

    private ResponseEntity<ApiError> protobufError(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status)
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .body(error);
    }
}
