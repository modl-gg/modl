package gg.modl.backend.storage.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import gg.modl.backend.storage.service.SignedUploadReceiver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ObjectStorageControllerTest {
    private static final String IMAGE_KEY = "modl_instance/evidence/PUN-1/abc-shot.png";
    private static final String TEXT_KEY = "modl_instance/tickets/T-1/abc-notes.txt";

    @TempDir
    Path root;

    private LocalObjectStorage storage;
    private StorageReservationRepository reservationRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
        reservationRepository = mock(StorageReservationRepository.class);
        SignedUploadReceiver receiver = new SignedUploadReceiver(storage, reservationRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(new ObjectStorageController(storage, receiver)).build();
    }

    @Test
    void signedUploadsAreStoredAndServedInlineWithASandboxPolicy() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 9);

        mockMvc.perform(put(URI.create(target.url())).contentType("image/png").content("png-bytes".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isOk());

        mockMvc.perform(get(storage.publicUrl(IMAGE_KEY)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("inline"));
        verify(reservationRepository).markLanded(IMAGE_KEY);
    }

    @Test
    void nonMediaObjectsAreServedAsAttachments() throws Exception {
        storage.write(TEXT_KEY, new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2, "text/plain");

        mockMvc.perform(get(storage.publicUrl(TEXT_KEY)))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("attachment"));
    }

    @Test
    void uploadsWithoutAValidSignatureAreForbidden() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 4);

        mockMvc.perform(put(URI.create(target.url().replace("len=4", "len=4096"))).contentType("image/png").content(new byte[4096]))
            .andExpect(status().isForbidden());
        assertThat(storage.exists(IMAGE_KEY)).isFalse();
        verify(reservationRepository, never()).markLanded(any());
    }

    @Test
    void uploadsLargerThanTheGrantAreRejected() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 4);

        mockMvc.perform(put(URI.create(target.url())).contentType("image/png").content(new byte[5]))
            .andExpect(status().is(413));
    }

    @Test
    void uploadsShorterThanTheGrantAreRejected() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 8);

        mockMvc.perform(put(URI.create(target.url())).contentType("image/png").content(new byte[5]))
            .andExpect(status().isBadRequest());
        assertThat(storage.exists(IMAGE_KEY)).isFalse();
    }

    @Test
    void aSignedUrlCannotOverwriteAnExistingObject() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 4);
        mockMvc.perform(put(URI.create(target.url())).contentType("image/png").content("abcd".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isOk());

        mockMvc.perform(put(URI.create(target.url())).contentType("image/png").content("wxyz".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isConflict());
        try (InputStream input = storage.read(IMAGE_KEY)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcd");
        }
    }

    @Test
    void uploadsWithAnotherContentTypeAreRejected() throws Exception {
        UploadTarget target = storage.presignUpload(IMAGE_KEY, "image/png", 16);

        mockMvc.perform(put(URI.create(target.url())).contentType("text/html").content(new byte[4]))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void anotherCaseOfAKeyIsNotServed() throws Exception {
        storage.write(TEXT_KEY, new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2, "text/plain");

        mockMvc.perform(get(storage.publicUrl(TEXT_KEY.replace("notes", "NOTES")))).andExpect(status().isNotFound());
    }

    @Test
    void missingObjectsAreNotFound() throws Exception {
        mockMvc.perform(get("/v1/storage/objects/modl_instance/evidence/missing.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/storage/objects/../etc/passwd")).andExpect(status().isNotFound());
    }
}
