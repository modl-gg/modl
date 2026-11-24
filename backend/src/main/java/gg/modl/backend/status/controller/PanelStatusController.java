package gg.modl.backend.status.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.status.SupportReportService;
import gg.modl.proto.modl.v1.StatusReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_STATUS)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelStatusController {
    private final SupportReportService reports;

    @GetMapping
    public StatusReportResponse status() {
        return StatusProtoMapper.toResponse(reports.report());
    }
}
