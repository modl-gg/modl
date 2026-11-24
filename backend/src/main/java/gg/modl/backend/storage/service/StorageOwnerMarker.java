package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.util.KeyedHmac;
import gg.modl.backend.instance.InstanceSecretsService;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StorageNamespace;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class StorageOwnerMarker {
    private static final String OWNER_LABEL = "modl-storage-owner";

    private final KeyedHmac ownerDerivation;

    public StorageOwnerMarker(InstanceSecretsService instanceSecretsService) {
        this.ownerDerivation = new KeyedHmac(instanceSecretsService::storageSigningKey);
    }

    public Verdict claim(ObjectStorage storage) {
        Verdict verdict = inspect(storage);
        if (verdict.isUnclaimed()) {
            storage.writeOwnershipMarker(StorageNamespace.NAME, ownerId());
            return Verdict.owned();
        }
        return verdict;
    }

    public Verdict inspect(ObjectStorage storage) {
        String namespace = StorageNamespace.NAME;
        String owner = ownerId();
        Optional<String> marker = storage.readOwnershipMarker(namespace);
        if (marker.isPresent()) {
            return marker.get().equals(owner)
                ? Verdict.owned()
                : Verdict.refused("the storage namespace " + namespace + " belongs to another modl installation (its "
                    + OwnershipMarker.key(namespace) + " does not match this database)");
        }
        if (hasObjects(storage)) {
            return Verdict.refused("the storage namespace " + namespace + " holds objects but no ownership marker; if it belongs"
                + " only to this installation, store the text " + owner + " as " + OwnershipMarker.key(namespace));
        }
        return Verdict.unclaimed();
    }

    private static boolean hasObjects(ObjectStorage storage) {
        try (Stream<ListedObject> objects = storage.list(StorageNamespace.KEY_PREFIX)) {
            return objects.findAny().isPresent();
        }
    }

    private String ownerId() {
        return ownerDerivation.signHex(OWNER_LABEL);
    }

    public record Verdict(boolean isOwned, boolean isUnclaimed, @Nullable String refusalReason) {
        static Verdict owned() {
            return new Verdict(true, false, null);
        }

        static Verdict unclaimed() {
            return new Verdict(false, true, null);
        }

        static Verdict refused(String reason) {
            return new Verdict(false, false, reason);
        }

        public String requireRefusalReason() {
            if (refusalReason == null) {
                throw new IllegalStateException("Only a refused ownership verdict carries a reason");
            }
            return refusalReason;
        }
    }
}
