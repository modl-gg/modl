package gg.modl.backend.hostedimport.storage;

import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.local.ObjectKeyPaths;
import gg.modl.backend.storage.service.MediaValidationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ImportedKeyPlan {
    static final String IMPORTED_SEGMENT = "imported";
    private static final String TEMPORARY_ENTITY = "new";
    private static final Set<String> TEMPORARY_FOLDERS = Set.of("tickets", "appeal", "appeals");
    private static final String NEUTRAL_EXTENSION = ".bin";
    private static final String DERIVED_NAME_PREFIX = "i-";
    private static final int DERIVED_HEX = 32;
    private static final int MAX_EXTENSION = 10;

    private final Map<String, PlannedObject> byOriginalKey;

    private ImportedKeyPlan(Map<String, PlannedObject> byOriginalKey) {
        this.byOriginalKey = byOriginalKey;
    }

    public static ImportedKeyPlan of(ArchiveManifest manifest, IssueLog issues) {
        Map<String, PlannedObject> planned = new LinkedHashMap<>();
        Set<String> foldedTaken = new HashSet<>();
        Set<String> folders = folderPrefixes(manifest);
        for (ArchiveManifest.StoredObjectRecord object : manifest.objects()) {
            String relative = object.key().substring(manifest.keyPrefix().length());
            String preferred = StorageNamespace.KEY_PREFIX + withoutTemporaryEntity(relative);
            String safe = withSafeExtension(preferred);
            boolean storable = ObjectKeyPaths.isValidKey(safe) && !folders.contains(safe.toLowerCase(Locale.ROOT));
            String local = storable ? safe : derived(object.key(), safe);
            if (!foldedTaken.add(local.toLowerCase(Locale.ROOT))) {
                local = derived(object.key(), safe);
                foldedTaken.add(local.toLowerCase(Locale.ROOT));
                issues.report(IssueCode.FILE_CASE_CONFLICT, object.key());
            } else if (!local.equals(preferred)) {
                issues.report(IssueCode.FILE_KEY_RENAMED, object.key());
            }
            String contentType = allowedContentType(local, object.contentType());
            if (!contentType.equals(object.contentType())) {
                issues.report(IssueCode.FILE_TYPE_NEUTRALISED, object.key());
            }
            planned.put(object.key(), new PlannedObject(object, local, contentType));
        }
        return new ImportedKeyPlan(planned);
    }

    public Optional<PlannedObject> forOriginalKey(String originalKey) {
        return Optional.ofNullable(byOriginalKey.get(originalKey));
    }

    public boolean isExported(String originalKey) {
        return byOriginalKey.containsKey(originalKey);
    }

    public List<PlannedObject> objects() {
        return List.copyOf(byOriginalKey.values());
    }

    public int size() {
        return byOriginalKey.size();
    }

    private static Set<String> folderPrefixes(ArchiveManifest manifest) {
        Set<String> folders = new HashSet<>();
        for (ArchiveManifest.StoredObjectRecord object : manifest.objects()) {
            String relative = object.key().substring(manifest.keyPrefix().length());
            String local = StorageNamespace.KEY_PREFIX + withoutTemporaryEntity(relative);
            int slash = local.indexOf('/');
            while (slash > 0) {
                folders.add(local.substring(0, slash).toLowerCase(Locale.ROOT));
                slash = local.indexOf('/', slash + 1);
            }
        }
        return folders;
    }

    static String withoutTemporaryEntity(String relative) {
        String[] segments = relative.split("/", -1);
        if (segments.length > 2 && TEMPORARY_FOLDERS.contains(segments[0]) && TEMPORARY_ENTITY.equalsIgnoreCase(segments[1].trim())) {
            segments[1] = IMPORTED_SEGMENT;
        }
        return String.join("/", segments);
    }

    private static String withSafeExtension(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        return MediaValidationService.dangerousExtension(name)
            .map(extension -> key.substring(0, key.length() - extension.length()) + NEUTRAL_EXTENSION)
            .orElse(key);
    }

    static String derived(String originalKey, String preferred) {
        String[] segments = preferred.split("/", -1);
        StringBuilder folder = new StringBuilder(StorageNamespace.NAME);
        for (int i = 1; i < segments.length - 1; i++) {
            String candidate = folder + "/" + segments[i];
            if (!ObjectKeyPaths.isValidKey(candidate + "/x")) {
                break;
            }
            folder.append('/').append(segments[i]);
        }
        if (folder.toString().equals(StorageNamespace.NAME)) {
            folder.append('/').append(IMPORTED_SEGMENT);
        }
        String name = DERIVED_NAME_PREFIX + hex(originalKey).substring(0, DERIVED_HEX) + safeExtension(segments[segments.length - 1]);
        return folder + "/" + name;
    }

    private static String safeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > MAX_EXTENSION) {
            return "";
        }
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        boolean plain = extension.substring(1).chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
        if (!plain || extension.length() < 2) {
            return "";
        }
        return MediaValidationService.dangerousExtension(extension).isPresent() ? NEUTRAL_EXTENSION : extension;
    }

    private static String allowedContentType(String localKey, String declared) {
        String type = declared == null ? StoredObject.DEFAULT_CONTENT_TYPE : declared.trim().toLowerCase(Locale.ROOT);
        String[] segments = localKey.split("/");
        String folder = segments.length > 1 ? segments[1] : "";
        Optional<MediaCategory> category = Arrays.stream(MediaCategory.values())
            .filter(candidate -> candidate.configKey().equals(folder) || candidate.uploadType().equals(folder))
            .findFirst();
        boolean allowed = category
            .map(known -> known.allows(type))
            .orElseGet(() -> Arrays.stream(MediaCategory.values()).anyMatch(candidate -> candidate.allows(type)));
        return allowed ? type : StoredObject.DEFAULT_CONTENT_TYPE;
    }

    private static String hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record PlannedObject(ArchiveManifest.StoredObjectRecord source, String localKey, String contentType) {
        public String fileName() {
            String key = source.key();
            return key.substring(key.lastIndexOf('/') + 1);
        }
    }
}
