package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.ticket.TicketHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TicketUploadsTest {
    private static final String KEPT = TicketHarness.OBJECTS_PREFIX + "kept.png";
    private static final String SWEPT = TicketHarness.OBJECTS_PREFIX + "swept.png";

    private final StorageFileRepository storageFiles = mock(StorageFileRepository.class);
    private final TicketUploads uploads = new TicketUploads(TicketHarness.localUrls(), storageFiles);

    @Test
    void onlyTheSweptFilesAreRemovedFromAttachmentsAndNestedAnswers() {
        when(storageFiles.markAttached(any())).thenReturn(1);
        when(storageFiles.findByKeys(any()))
            .thenReturn(List.of(new StorageFile("kept.png", "kept.png", 1, "image/png", "ticket", TicketHarness.NOW)));

        TicketUploads.Attached attached = uploads.attach(
            List.of(Map.of("url", KEPT), Map.of("url", SWEPT), SWEPT, Map.of("url", "https://cdn.example/a.png")),
            Map.of(
                "files", List.of(Map.of("url", SWEPT, "fileName", "swept.png"), Map.of("url", KEPT)),
                "single", SWEPT,
                "text", "hello"));

        assertEquals(List.of(Map.of("url", KEPT), Map.of("url", "https://cdn.example/a.png")), attached.attachments());
        assertEquals(Map.of("files", List.of(Map.of("url", KEPT)), "text", "hello"), attached.formData());
    }

    @Test
    void externalLinksNeverTouchStorage() {
        uploads.attach(List.of(Map.of("url", "https://cdn.example/a.png")));

        verifyNoInteractions(storageFiles);
    }
}
