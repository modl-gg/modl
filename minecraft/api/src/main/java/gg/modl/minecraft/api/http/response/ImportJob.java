package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJob {
    private String id, type, status, endReason, error, startedFrom, startedAddress, scopedPunishments, ipHistory;
    private Integer recordsProcessed, recordsSkipped, totalRecords, exportFailed, exportSkipped;
}
