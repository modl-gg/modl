package gg.modl.backend.storage.service;

import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.SignedUpload;
import gg.modl.backend.storage.local.SignedUploadOutcome;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignedUploadReceiver {
    private final LocalObjectStorage storage;
    private final StorageReservationRepository reservationRepository;

    public SignedUploadOutcome receive(SignedUpload upload) {
        SignedUploadOutcome outcome = storage.acceptSignedUpload(upload);
        if (outcome == SignedUploadOutcome.STORED) {
            reservationRepository.markLanded(upload.grant().key());
        }
        return outcome;
    }
}
