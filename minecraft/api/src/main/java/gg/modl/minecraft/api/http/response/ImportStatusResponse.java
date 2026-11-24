package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ImportStatusResponse {
    private ImportJob job;
    private boolean setupReady;
    private long maxUploadBytes;
    private int ipHistoryDays;
    private Long ipHistoryKeptBefore;
}
