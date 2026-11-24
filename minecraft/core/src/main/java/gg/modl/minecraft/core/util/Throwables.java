package gg.modl.minecraft.core.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Throwables {
    private Throwables() {
    }

    public static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while ((root instanceof CompletionException || root instanceof ExecutionException) && root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
