package gg.modl.backend.infrastructure.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.SimpleResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

class GlobalExceptionHandlerDatabaseTest {
    private static final String JSON_PATH = "/v1/panel/database-failure/";
    private static final String PROTOBUF_PATH = RESTMappingV3.PREFIX_MINECRAFT + "/database-failure/";
    private static final String SQL_DETAIL = "UNIQUE constraint failed: staff.email";
    private static final long CURRENT_VERSION = 7;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
            .build();
    }

    @ParameterizedTest
    @EnumSource(DatabaseFailure.class)
    void aJsonErrorCarriesTheMappedStatusAndCode(DatabaseFailure failure) throws Exception {
        MvcResult result = json(failure);

        Map<?, ?> body = ApplicationJson.MAPPER.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        assertEquals(failure.status, result.getResponse().getStatus());
        assertEquals(failure.status, body.get("status"));
        assertEquals(failure.code, body.get("code"));
    }

    @ParameterizedTest
    @EnumSource(DatabaseFailure.class)
    void aProtobufErrorCarriesTheMappedStatusAndCode(DatabaseFailure failure) throws Exception {
        MvcResult result = protobuf(failure);

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(failure.status, result.getResponse().getStatus());
        assertEquals(failure.status, error.getStatusCode());
        assertEquals(failure.code, error.getCode());
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseFailure.class, names = {"DUPLICATE_ROW", "REFERENCE_VIOLATION"})
    void aRowConflictDoesNotExposeTheDatabaseError(DatabaseFailure failure) throws Exception {
        Map<?, ?> body = ApplicationJson.MAPPER.readValue(json(failure).getResponse().getContentAsByteArray(), Map.class);
        ApiError error = ApiError.parseFrom(protobuf(failure).getResponse().getContentAsByteArray());

        assertEquals("The request conflicts with an existing record.", body.get("message"));
        assertFalse(error.getMessage().contains(SQL_DETAIL), error.getMessage());
    }

    @Test
    void aStaleClientVersionReportsTheCurrentVersion() throws Exception {
        Map<?, ?> body = ApplicationJson.MAPPER.readValue(
            json(DatabaseFailure.STALE_CLIENT_VERSION).getResponse().getContentAsByteArray(), Map.class);

        assertEquals((int) CURRENT_VERSION, ((Number) body.get("currentVersion")).intValue());
    }

    @Test
    void aValueTooLongNamesItsLimitButNoColumn() throws Exception {
        ApiError error = ApiError.parseFrom(protobuf(DatabaseFailure.VALUE_TOO_LONG).getResponse().getContentAsByteArray());

        assertEquals("A value is longer than the 64 characters allowed", error.getMessage());
    }

    @Test
    void aBusyDatabaseAsksJsonClientsToRetryAfterTheBusyPeriod() throws Exception {
        MvcResult result = json(DatabaseFailure.DATABASE_BUSY);

        ErrorResponseDTO error = ApplicationJson.MAPPER.readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals("2", result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(2L, error.retryAfterSeconds());
    }

    @Test
    void aBusyDatabaseAsksProtobufClientsToRetryAfterTheBusyPeriod() throws Exception {
        MvcResult result = protobuf(DatabaseFailure.DATABASE_BUSY);

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals("2", result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(2L, error.getRetryMetadata().getRetryAfterSeconds());
        assertEquals(2000L, error.getRetryMetadata().getRetryAfterMs());
    }

    private MvcResult json(DatabaseFailure failure) throws Exception {
        return mockMvc.perform(get(JSON_PATH + failure.name()).accept(MediaType.APPLICATION_JSON))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();
    }

    private MvcResult protobuf(DatabaseFailure failure) throws Exception {
        return mockMvc.perform(get(PROTOBUF_PATH + failure.name()).accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();
    }

    enum DatabaseFailure {
        DUPLICATE_ROW(409, "CONFLICT", () -> new DuplicateRowException(SQL_DETAIL, new SQLException(SQL_DETAIL))),
        REFERENCE_VIOLATION(409, "CONFLICT", () -> new ReferenceViolationException(SQL_DETAIL, new SQLException(SQL_DETAIL))),
        CONCURRENT_UPDATE(409, "CONFLICT", () -> new ConcurrentUpdateException("punishment p1 changed")),
        STALE_CLIENT_VERSION(409, "CONFLICT", () -> new StaleClientVersionException("Settings version conflict", CURRENT_VERSION)),
        VALUE_TOO_LONG(400, "INVALID_ARGUMENT", () -> new ValueTooLongException(64)),
        DATABASE_BUSY(503, "DATABASE_BUSY", () -> new DatabaseBusyException("busy", Duration.ofMillis(1500))),
        DATABASE_UNAVAILABLE(503, "DATABASE_UNAVAILABLE", () -> new DatabaseUnavailableException(Reason.SHUTTING_DOWN)),
        DATABASE_LOCK_LOST(503, "DATABASE_UNAVAILABLE", () -> new DatabaseUnavailableException(Reason.LOCK_LOST)),
        DATABASE_DISK_FULL(503, "DATABASE_DISK_FULL", () -> new DatabaseUnavailableException(Reason.DISK_FULL));

        private final int status;
        private final String code;
        private final Supplier<RuntimeException> exception;

        DatabaseFailure(int status, String code, Supplier<RuntimeException> exception) {
            this.status = status;
            this.code = code;
            this.exception = exception;
        }
    }

    @Controller
    static class FailingController {
        @GetMapping({JSON_PATH + "{failure}", PROTOBUF_PATH + "{failure}"})
        @ResponseBody
        SimpleResponse fail(@PathVariable DatabaseFailure failure) {
            throw failure.exception.get();
        }
    }
}
