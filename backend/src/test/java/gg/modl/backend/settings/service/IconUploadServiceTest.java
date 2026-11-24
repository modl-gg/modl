package gg.modl.backend.settings.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadRecordFailedException;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.storage.service.UploadConfirmation;
import gg.modl.backend.support.TestServers;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class IconUploadServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String CURRENT_ICON = "modl_instance/icons_homepage/current.png";
    private static final String OLD_ICON = "modl_instance/icons_homepage/old.png";
    private static final String RECENT_ICON = "modl_instance/icons_homepage/recent.png";

    private final Server server = TestServers.server("server", "admin@example.com");
    private ObjectStorage objectStorage;
    private StorageLimitPolicy storageLimitPolicy;
    private StorageMetadataService storageMetadataService;
    private StorageDeletionService storageDeletionService;
    private GeneralSettingsService generalSettingsService;
    private StoredObjectUrlResolver storedObjectUrlResolver;
    private IconUploadService service;
    private MultipartFile file;

    @BeforeEach
    void setUp() throws Exception {
        objectStorage = mock(ObjectStorage.class);
        storageLimitPolicy = mock(StorageLimitPolicy.class);
        storageMetadataService = mock(StorageMetadataService.class);
        storageDeletionService = mock(StorageDeletionService.class);
        generalSettingsService = mock(GeneralSettingsService.class);
        storedObjectUrlResolver = mock(StoredObjectUrlResolver.class);
        service = new IconUploadService(new PublicImageUploader(objectStorage, storageLimitPolicy, storageMetadataService,
            storageDeletionService, storedObjectUrlResolver, Clock.fixed(NOW, ZoneOffset.UTC)), generalSettingsService);
        file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("icon.png");
        when(file.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(new byte[1024]));
        when(storageLimitPolicy.maxUploadBytes(MediaCategory.SERVER_ICON)).thenReturn(10L * 1024 * 1024);
        when(storageMetadataService.confirmUpload(eq(server), anyString(), eq(1024L), eq("image/png"))).thenReturn(UploadConfirmation.RECORDED);
        when(objectStorage.publicUrl(anyString())).thenAnswer(invocation -> "/v1/storage/objects/" + invocation.getArgument(0));
        when(generalSettingsService.getGeneralSettings(server))
            .thenReturn(GeneralSettings.builder().homepageIconUrl("/v1/storage/objects/" + CURRENT_ICON).build());
        when(storedObjectUrlResolver.keyOf("/v1/storage/objects/" + CURRENT_ICON)).thenReturn(Optional.of(CURRENT_ICON));
        when(objectStorage.list("modl_instance/icons_homepage/")).thenReturn(Stream.empty());
    }

    @Test
    void uploadReservesStoresAndRecordsTheIconAndReturnsItsRelativePublicUrl() {
        String url = service.uploadIcon(server, file, "homepage");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageLimitPolicy).reserve(key.capture(), eq(1024L), eq(NOW));
        assertTrue(key.getValue().startsWith("modl_instance/icons_homepage/"));
        assertEquals("/v1/storage/objects/" + key.getValue(), url);
        verify(objectStorage).write(eq(key.getValue()), any(InputStream.class), eq(1024L), eq("image/png"));
    }

    @Test
    void supersededIconsOlderThanTheGraceAreRemovedButTheReferencedAndRecentOnesStay() {
        when(objectStorage.list("modl_instance/icons_homepage/")).thenReturn(Stream.of(
            new ListedObject(CURRENT_ICON, 1L, NOW.minus(PublicImageUploader.SUPERSEDED_IMAGE_GRACE).minusSeconds(60)),
            new ListedObject(OLD_ICON, 1L, NOW.minus(PublicImageUploader.SUPERSEDED_IMAGE_GRACE).minusSeconds(60)),
            new ListedObject(RECENT_ICON, 1L, NOW.minusSeconds(60))
        ));

        service.uploadIcon(server, file, "homepage");

        verify(storageDeletionService).delete(server, List.of(OLD_ICON));
    }

    @Test
    void aFailedSupersededIconCleanupDoesNotFailTheUpload() {
        when(objectStorage.list("modl_instance/icons_homepage/")).thenThrow(new IllegalStateException("listing down"));

        assertTrue(service.uploadIcon(server, file, "homepage").startsWith("/v1/storage/objects/modl_instance/icons_homepage/"));
    }

    @Test
    void uploadIsRefusedWhenTheStorageLimitIsReached() {
        doThrow(new StorageLimitExceededException("full")).when(storageLimitPolicy).reserve(anyString(), eq(1024L), eq(NOW));

        assertThrows(StorageLimitExceededException.class, () -> service.uploadIcon(server, file, "homepage"));
        verify(objectStorage, never()).write(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void uploadDiscardsTheObjectWhenMetadataCannotBeRecorded() {
        when(storageMetadataService.confirmUpload(eq(server), anyString(), eq(1024L), eq("image/png"))).thenReturn(UploadConfirmation.EXPIRED);

        assertThrows(UploadRecordFailedException.class, () -> service.uploadIcon(server, file, "homepage"));
        verify(storageDeletionService).discard(eq(server), anyString());
        verify(storageDeletionService, never()).delete(any(), anyCollection());
    }

    @Test
    void uploadDiscardsTheObjectWhenTheWriteFails() {
        doThrow(new StorageLimitExceededException("disk")).when(objectStorage)
            .write(anyString(), any(InputStream.class), eq(1024L), eq("image/png"));

        assertThrows(StorageLimitExceededException.class, () -> service.uploadIcon(server, file, "homepage"));
        verify(storageDeletionService).discard(eq(server), anyString());
        verify(storageMetadataService, never()).confirmUpload(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void anObjectThatAlreadyExistsIsNeverDiscarded() {
        doThrow(new StoredObjectExistsException()).when(objectStorage)
            .write(anyString(), any(InputStream.class), eq(1024L), eq("image/png"));

        assertThrows(StoredObjectExistsException.class, () -> service.uploadIcon(server, file, "homepage"));
        verify(storageDeletionService, never()).discard(any(), anyString());
    }

    @Test
    void uploadRejectsNonImageTypes() {
        when(file.getContentType()).thenReturn("image/svg+xml");

        assertThrows(ValidationException.class, () -> service.uploadIcon(server, file, "homepage"));
        verify(storageLimitPolicy, never()).reserve(anyString(), anyLong(), any());
    }

    @Test
    void uploadRejectsUnknownIconTypes() {
        assertThrows(ValidationException.class, () -> service.uploadIcon(server, file, "banner"));
    }
}
