package gg.modl.backend.settings.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SettingsType;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class VersionedSettingsSupport<T> {
    private final SettingsDocumentService documentService;
    private final String type;
    private final Function<Map<String, Object>, T> decoder;

    private VersionedSettingsSupport(
        SettingsDocumentService documentService,
        String type,
        Function<Map<String, Object>, T> decoder
    ) {
        this.documentService = documentService;
        this.type = type;
        this.decoder = decoder;
    }

    public static <T> VersionedSettingsSupport<T> of(
        SettingsDocumentService documentService,
        SettingsType type,
        Function<Map<String, Object>, T> decoder
    ) {
        return new VersionedSettingsSupport<>(documentService, type.id(), decoder);
    }

    public T get(Server server) {
        return state(server).data();
    }

    public VersionedSettings<T> state(Server server) {
        return envelope(documentService.getRawState(type));
    }

    public VersionedSettings<T> save(Server server, long expectedVersion, Map<String, Object> data) {
        return update(server, expectedVersion, current -> data);
    }

    public VersionedSettings<T> update(Server server, long expectedVersion, UnaryOperator<Map<String, Object>> change) {
        return envelope(documentService.updateExpecting(type, expectedVersion, change));
    }

    public VersionedSettings<T> updateLatest(Server server, UnaryOperator<Map<String, Object>> change) {
        return envelope(documentService.updateLatest(type, change));
    }

    public void delete(Server server) {
        documentService.deleteState(type);
    }

    private VersionedSettings<T> envelope(SettingsDocumentService.RawSettingsState raw) {
        return new VersionedSettings<>(decoder.apply(raw.data()), raw.version(), raw.updatedAt());
    }
}
