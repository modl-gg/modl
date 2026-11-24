package gg.modl.backend.realtime.publish;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import java.util.ArrayList;
import java.util.List;

record SizedBatches<T extends MessageLite>(List<List<T>> batches, List<T> oversized) {

    static <T extends MessageLite> SizedBatches<T> pack(List<T> items, int fieldNumber, int budgetBytes) {
        List<List<T>> batches = new ArrayList<>();
        List<T> oversized = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int currentBytes = 0;
        for (T item : items) {
            int itemBytes = CodedOutputStream.computeMessageSize(fieldNumber, item);
            if (itemBytes > budgetBytes) {
                oversized.add(item);
                continue;
            }
            if (currentBytes + itemBytes > budgetBytes) {
                batches.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(item);
            currentBytes += itemBytes;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return new SizedBatches<>(List.copyOf(batches), List.copyOf(oversized));
    }
}
