package gg.modl.backend.migration.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

public final class TestImports {
    private TestImports() {
    }

    public static RunningImports none() {
        return running(List::of);
    }

    @SuppressWarnings("unchecked")
    public static RunningImports running(Supplier<List<ConcurrentImport>> imports) {
        ObjectProvider<ConcurrentImport> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(invocation -> List.copyOf(imports.get()).stream());
        return new RunningImports(provider);
    }

    public static ConcurrentImport of(ImportKind kind, BooleanSupplier inProgress) {
        return new ConcurrentImport() {
            @Override
            public ImportKind kind() {
                return kind;
            }

            @Override
            public boolean isInProgress() {
                return inProgress.getAsBoolean();
            }
        };
    }
}
