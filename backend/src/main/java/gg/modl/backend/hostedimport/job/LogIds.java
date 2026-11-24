package gg.modl.backend.hostedimport.job;

import gg.modl.backend.log.repository.ImportedLogRepository;
import gg.modl.backend.log.repository.ImportedLogTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogIds {
    private final ImportedLogRepository logs;

    List<Long> assign(ImportedLogTable table, List<Long> preferred) {
        List<Long> assigned = new ArrayList<>(preferred);
        Set<Long> used = new HashSet<>();
        Map<Integer, Long> pending = new HashMap<>();
        for (int i = 0; i < preferred.size(); i++) {
            pending.put(i, preferred.get(i));
        }
        while (!pending.isEmpty()) {
            Set<Long> taken = logs.takenIds(table, pending.values());
            Map<Integer, Long> next = new HashMap<>();
            for (Map.Entry<Integer, Long> entry : new TreeMap<>(pending).entrySet()) {
                long candidate = entry.getValue();
                if (taken.contains(candidate) || !used.add(candidate)) {
                    next.put(entry.getKey(), candidate + 1);
                } else {
                    assigned.set(entry.getKey(), candidate);
                }
            }
            pending = next;
        }
        return assigned;
    }
}
