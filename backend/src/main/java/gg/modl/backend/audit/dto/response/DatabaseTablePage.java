package gg.modl.backend.audit.dto.response;

import java.util.List;
import java.util.Map;

public record DatabaseTablePage(List<Map<String, Object>> rows, long total, int limit, int skip) {
}
