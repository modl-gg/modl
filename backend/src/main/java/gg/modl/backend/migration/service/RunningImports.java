package gg.modl.backend.migration.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RunningImports {
    private final ObjectProvider<ConcurrentImport> imports;

    public RunningImports(ObjectProvider<ConcurrentImport> imports) {
        this.imports = imports;
    }

    public boolean any() {
        return imports.stream().anyMatch(ConcurrentImport::isInProgress);
    }

    public boolean anyBesides(ImportKind own) {
        return imports.stream().filter(running -> running.kind() != own).anyMatch(ConcurrentImport::isInProgress);
    }
}
