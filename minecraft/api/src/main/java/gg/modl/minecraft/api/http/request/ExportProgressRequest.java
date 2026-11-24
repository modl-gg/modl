package gg.modl.minecraft.api.http.request;

import lombok.Value;

@Value
public class ExportProgressRequest {
    String phase;
    int recordsProcessed, totalRecords, exportFailed, exportSkipped;
}
