package gg.modl.backend.hostedimport.controller;

import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.service.HostedImportService;
import gg.modl.backend.hostedimport.service.ImportRequests;
import gg.modl.backend.hostedimport.service.HostedImportException;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.BeginHostedImportUploadRequest;
import gg.modl.proto.modl.v1.HostedImportActionRequest;
import gg.modl.proto.modl.v1.HostedImportPreview;
import gg.modl.proto.modl.v1.HostedImportReport;
import gg.modl.proto.modl.v1.HostedImportStaffPage;
import gg.modl.proto.modl.v1.HostedImportState;
import gg.modl.proto.modl.v1.StartHostedImportDownloadRequest;
import gg.modl.proto.modl.v1.StartHostedImportRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETUP_IMPORT)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelHostedImportController {
    private final HostedImportService service;

    @GetMapping
    public HostedImportState state(HttpServletRequest request) {
        return HostedImportProtoMapper.toState(service.state(RequestUtil.getRequestServer(request)));
    }

    @PostMapping("/upload")
    public HostedImportState beginUpload(@RequestBody BeginHostedImportUploadRequest body, HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        service.beginUpload(server, new ImportRequests.BeginUpload(body.getSizeBytes(), blankToNull(body.getFileName()),
            body.hasExpectedSha256() ? body.getExpectedSha256() : null));
        return state(request);
    }

    @PutMapping(path = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public HostedImportState receiveChunk(@RequestParam String importId, @RequestParam long offset, HttpServletRequest request) {
        try {
            service.receiveChunk(importId, offset, request.getContentLengthLong(), request.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return state(request);
    }

    @PostMapping("/upload/complete")
    public HostedImportState completeUpload(@RequestBody HostedImportActionRequest body, HttpServletRequest request) {
        service.completeUpload(body.getImportId());
        return state(request);
    }

    @PostMapping("/download")
    public HostedImportState download(@RequestBody StartHostedImportDownloadRequest body, HttpServletRequest request) {
        service.startDownload(RequestUtil.getRequestServer(request), new ImportRequests.StartDownload(body.getUrl(),
            body.hasExpectedSha256() ? body.getExpectedSha256() : null));
        return state(request);
    }

    @GetMapping("/preview")
    public HostedImportPreview preview(HttpServletRequest request) {
        return HostedImportProtoMapper.toPreview(service.preview(RequestUtil.getRequestServer(request)));
    }

    @GetMapping("/staff")
    public HostedImportStaffPage staff(@RequestParam(required = false) @Nullable String cursor, HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        int offset = offset(cursor);
        List<StaffPlan.Candidate> page = service.staffPage(server, offset);
        return HostedImportProtoMapper.toStaffPage(page, offset, HostedImportService.staffPageSize(),
            service.preview(server).staff().size());
    }

    @PostMapping("/start")
    public HostedImportState start(@RequestBody StartHostedImportRequest body, HttpServletRequest request) {
        service.start(RequestUtil.getRequestServer(request), new ImportRequests.Start(body.getImportId(),
            new HashSet<>(body.getStaffIdsList()), body.getMergeArchivedOwner(), HostedImportProtoMapper.fromProto(body.getRetention())));
        return state(request);
    }

    @PostMapping("/resume")
    public HostedImportState resume(@RequestBody HostedImportActionRequest body, HttpServletRequest request) {
        service.resume(body.getImportId());
        return state(request);
    }

    @PostMapping("/discard")
    public HostedImportState discard(@RequestBody HostedImportActionRequest body, HttpServletRequest request) {
        service.discard(body.getImportId());
        return state(request);
    }

    @PostMapping("/abandon")
    public HostedImportState abandon(@RequestBody HostedImportActionRequest body, HttpServletRequest request) {
        service.abandon(RequestUtil.getRequestServer(request), body.getImportId());
        return state(request);
    }

    @PostMapping("/finish")
    public HostedImportState finish(@RequestBody HostedImportActionRequest body, HttpServletRequest request) {
        service.finish(RequestUtil.getRequestServer(request), body.getImportId());
        return state(request);
    }

    @GetMapping("/report")
    public HostedImportReport report() {
        return HostedImportProtoMapper.toReport(service.report(), service.reportedRetention().orElse(null));
    }

    private static int offset(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new HostedImportException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Unknown page cursor");
        }
    }

    @Nullable
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
