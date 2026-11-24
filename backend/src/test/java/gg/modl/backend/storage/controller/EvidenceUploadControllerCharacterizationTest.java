package gg.modl.backend.storage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.dto.response.PresignUploadResponse;
import gg.modl.backend.storage.dto.response.UploadResponse;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.EvidenceUploadService;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.storage.service.MediaValidationService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.storage.service.UploadOrchestrationService;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EvidenceUploadControllerCharacterizationTest {

    private static final long MAX_EVIDENCE_BYTES = 50L * 1024 * 1024;
    private static final String KEY = "modl_instance/evidence/PUN-1/file.png";
    private static final String PRESIGN_BODY =
        "{\"fileName\":\"test.png\",\"contentType\":\"image/png\",\"fileSize\":1024}";
    private static final String CONFIRM_BODY = "{\"key\":\"" + KEY + "\"}";
    private static final String URL = "/v1/storage/objects/" + KEY;
    private static final String SUBMIT_BODY =
        "{\"evidence\":[{\"url\":\"" + URL
            + "\",\"fileName\":\"file.png\",\"fileType\":\"image/png\",\"fileSize\":1024}]}";

    private EvidenceUploadTokenService tokenService;
    private PlayerRepository playerRepository;
    private PunishmentEvidenceService punishmentEvidenceService;
    private StorageMetadataService storageMetadataService;
    private UploadOrchestrationService uploadOrchestrationService;
    private StoredObjectUrlResolver storedObjectUrlResolver;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        tokenService = mock(EvidenceUploadTokenService.class);
        playerRepository = mock(PlayerRepository.class);
        punishmentEvidenceService = mock(PunishmentEvidenceService.class);
        storageMetadataService = mock(StorageMetadataService.class);
        uploadOrchestrationService = mock(UploadOrchestrationService.class);
        storedObjectUrlResolver = mock(StoredObjectUrlResolver.class);
        server = TestServers.server("server", "admin@example.com");
        StorageFileRepository storageFileRepository = mock(StorageFileRepository.class);
        when(storageFileRepository.markAttached(anyCollection())).thenReturn(1);
        UnitOfWork unitOfWork = mock(UnitOfWork.class);
        when(unitOfWork.write(ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation.<Supplier<Object>>getArgument(0).get());
        InstanceContext instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(server);
        when(storedObjectUrlResolver.keyOf(URL)).thenReturn(Optional.of(KEY));
        when(tokenService.invalidateToken("token-1")).thenReturn(true);

        StorageLimitPolicy limitPolicy = mock(StorageLimitPolicy.class);
        when(limitPolicy.maxUploadBytes(MediaCategory.EVIDENCE)).thenReturn(MAX_EVIDENCE_BYTES);
        EvidenceUploadService service = new EvidenceUploadService(
            tokenService,
            playerRepository,
            instanceContext,
            new MediaValidationService(limitPolicy),
            punishmentEvidenceService,
            storageMetadataService,
            uploadOrchestrationService,
            storedObjectUrlResolver,
            storageFileRepository,
            unitOfWork
        );

        mockMvc = MockMvcBuilders
            .standaloneSetup(new EvidenceUploadController(service))
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();
    }

    private EvidenceUploadTokenService.UploadToken validToken() {
        return new EvidenceUploadTokenService.UploadToken(
            "token-1", "PUN-1", "player-1", "Moderator", Instant.now());
    }

    private String base() {
        return RESTMappingV1.PUBLIC_EVIDENCE_UPLOAD;
    }

    @Test
    void validateTokenInvalid() throws Exception {
        when(tokenService.validateToken("bad")).thenReturn(null);

        mockMvc.perform(get(base() + "/bad"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Invalid or expired upload token"));
    }

    @Test
    void validateTokenValid() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(playerRepository.findByMinecraftUuid(anyString(), eq(PlayerParts.USERNAMES))).thenReturn(Optional.empty());

        mockMvc.perform(get(base() + "/token-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.punishmentId").value("PUN-1"))
            .andExpect(jsonPath("$.playerName").value("Unknown"))
            .andExpect(jsonPath("$.issuerName").value("Moderator"))
            .andExpect(jsonPath("$.maxUploadBytes").value(MAX_EVIDENCE_BYTES));
    }

    @Test
    void presignInvalidToken() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(null);

        perform("/token-1/presign", PRESIGN_BODY)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Invalid or expired upload token"));
    }

    @Test
    void presignStorageFull() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(uploadOrchestrationService.presign(eq(server), any()))
            .thenReturn(new UploadOrchestrationService.PresignOutcome(
                UploadOrchestrationService.PresignStatus.STORAGE_FULL, "The storage limit of 1 GB has been reached", null));

        perform("/token-1/presign", PRESIGN_BODY)
            .andExpect(status().isInsufficientStorage())
            .andExpect(jsonPath("$.status").value(507))
            .andExpect(jsonPath("$.message").value("The storage limit of 1 GB has been reached"));
    }

    @Test
    void presignValidationFailed() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(uploadOrchestrationService.presign(eq(server), any()))
            .thenReturn(new UploadOrchestrationService.PresignOutcome(
                UploadOrchestrationService.PresignStatus.VALIDATION_FAILED, "File too large", null));

        perform("/token-1/presign", PRESIGN_BODY)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("File too large"));
    }

    @Test
    void presignSuccess() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(uploadOrchestrationService.presign(eq(server), any()))
            .thenReturn(new UploadOrchestrationService.PresignOutcome(
                UploadOrchestrationService.PresignStatus.SUCCESS, null,
                new PresignUploadResponse(
                    "https://upload.example.com",
                    KEY,
                    Instant.parse("2030-01-01T00:00:00Z"),
                    "PUT",
                    Map.of("x-amz-acl", "private"))));

        perform("/token-1/presign", PRESIGN_BODY)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.presignedUrl").value("https://upload.example.com"))
            .andExpect(jsonPath("$.key").value(KEY))
            .andExpect(jsonPath("$.expiresAt").value("2030-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.requiredHeaders['x-amz-acl']").value("private"));
    }

    @Test
    void confirmInvalidToken() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(null);

        perform("/token-1/confirm", CONFIRM_BODY)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Invalid or expired upload token"));
    }

    @Test
    void confirmInvalidKeyPrefixMismatch() throws Exception {
        String foreignKey = "modl_instance/evidence/OTHER-PUN/file.png";
        when(tokenService.validateToken("token-1")).thenReturn(validToken());

        perform("/token-1/confirm", "{\"key\":\"" + foreignKey + "\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.message").value("Upload key does not belong to this evidence token"));
    }

    @Test
    void confirmOrchestratedSuccess() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(uploadOrchestrationService.confirm(server, KEY))
            .thenReturn(new UploadOrchestrationService.ConfirmOutcome(
                UploadOrchestrationService.ConfirmStatus.SUCCESS,
                new UploadResponse(KEY, URL, "file.png", 42L, "image/png")));

        perform("/token-1/confirm", CONFIRM_BODY)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.key").value(KEY))
            .andExpect(jsonPath("$.url").value(URL))
            .andExpect(jsonPath("$.fileName").value("file.png"))
            .andExpect(jsonPath("$.size").value(42))
            .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void confirmOrchestratedUploadNotFound() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(uploadOrchestrationService.confirm(server, KEY))
            .thenReturn(new UploadOrchestrationService.ConfirmOutcome(
                UploadOrchestrationService.ConfirmStatus.UPLOAD_NOT_FOUND, null));

        perform("/token-1/confirm", CONFIRM_BODY)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Upload not found. File may not have been uploaded yet."));
    }

    @Test
    void submitInvalidToken() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(null);

        perform("/token-1/submit", SUBMIT_BODY)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Invalid or expired upload token"));
    }

    @Test
    void submitInvalidUrl() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(storedObjectUrlResolver.keyOf(URL)).thenReturn(Optional.empty());

        perform("/token-1/submit", SUBMIT_BODY)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("Invalid evidence URL"));
    }

    @Test
    void submitPunishmentNotFound() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(storageMetadataService.findConfirmedFiles(eq(server), any()))
            .thenReturn(Map.of(KEY, new StorageFile(KEY, "file.png", 42L, "image/png", "evidence", Instant.now())));
        when(punishmentEvidenceService.addUploadedEvidence(eq(server), eq("PUN-1"), eq("Moderator"), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        perform("/token-1/submit", SUBMIT_BODY)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.message").value("Punishment not found"));
    }

    @Test
    void submitSuccess() throws Exception {
        when(tokenService.validateToken("token-1")).thenReturn(validToken());
        when(storageMetadataService.findConfirmedFiles(eq(server), any()))
            .thenReturn(Map.of(KEY, new StorageFile(KEY, "file.png", 42L, "image/png", "evidence", Instant.now())));
        when(punishmentEvidenceService.addUploadedEvidence(eq(server), eq("PUN-1"), eq("Moderator"), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "ok", true, 1));

        perform("/token-1/submit", SUBMIT_BODY)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.message").value("Evidence uploaded successfully"));
    }

    private ResultActions perform(String path, String body) throws Exception {
        return mockMvc.perform(post(base() + path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }
}
