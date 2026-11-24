package gg.modl.backend.hostedimport.archive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

final class DocumentBudget {
    private static final long NODE_BYTES = 48;
    private static final long STRING_OVERHEAD = 40;
    private static final String ARRAY_ELEMENT = "[]";
    private static final Set<String> FOLDED_DATE_ARRAYS = Set.of("ipAddresses.[].logins");

    private final long heapLimit;
    private final Deque<String> path = new ArrayDeque<>();
    private long heap;

    DocumentBudget(long heapLimit) {
        this.heapLimit = heapLimit;
    }

    void enter(String name) {
        if (path.size() >= ArchiveLimits.MAX_DEPTH) {
            throw new UnreadableDocumentException("nesting too deep", false);
        }
        path.addLast(name);
    }

    void enterElement() {
        enter(ARRAY_ELEMENT);
    }

    void leave() {
        path.removeLast();
    }

    boolean foldsDates() {
        return FOLDED_DATE_ARRAYS.contains(String.join(".", path));
    }

    void node() {
        charge(NODE_BYTES);
    }

    String string(String value) {
        charge(STRING_OVERHEAD + 2L * value.length());
        return ImportedText.clean(value);
    }

    void bytes(long length) {
        charge(NODE_BYTES + length);
    }

    private void charge(long bytes) {
        heap += bytes;
        if (heap > heapLimit) {
            throw new UnreadableDocumentException("document too large", true);
        }
    }
}
