package gg.modl.backend.migration.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.migration.config.MigrationConfiguration;
import gg.modl.backend.migration.data.ImportOrigin;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.service.ImportUploadReceiver;
import gg.modl.backend.migration.service.MigrationService;
import gg.modl.proto.modl.v1.EndImportJobRequest;
import gg.modl.proto.modl.v1.ExportPhase;
import gg.modl.proto.modl.v1.ImportJobResponse;
import gg.modl.proto.modl.v1.ImportStatusResponse;
import gg.modl.proto.modl.v1.ReportExportProgressRequest;
import gg.modl.proto.modl.v1.StartImportJobRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_MIGRATION + "/jobs")
@RequiredArgsConstructor
public class MinecraftMigrationController {
    private final MigrationService migrationService;
    private final ImportUploadReceiver importUploadReceiver;
    private final MigrationProtoMapper mapper;
    private final MigrationConfiguration migrationConfiguration;
    private final ClientIpResolver clientIpResolver;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportJobResponse> start(@RequestBody StartImportJobRequest body, HttpServletRequest request) {
        MigrationStatus job = migrationService.start(
            RequestUtil.getRequestServer(request),
            body.getType(),
            ImportOrigin.reportedBy(body.getServerName(), clientIpResolver.resolve(request)),
            mapper.toScopedPunishments(body.getScopedPunishments()),
            mapper.toIpHistoryRetention(body.getIpHistory()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(job));
    }

    @GetMapping(path = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportStatusResponse> latest(HttpServletRequest request) {
        return ResponseEntity.ok(mapper.toStatusResponse(migrationService.overview(RequestUtil.getRequestServer(request))));
    }

    @GetMapping(path = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportJobResponse> job(@PathVariable String jobId) {
        return ResponseEntity.ok(toResponse(migrationService.job(jobId)));
    }

    @PostMapping(path = "/{jobId}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportJobResponse> reportExport(@PathVariable String jobId,
                                                          @RequestBody ReportExportProgressRequest body) {
        MigrationStatus job = migrationService.reportExport(jobId,
            body.getPhase() == ExportPhase.EXPORT_PHASE_UPLOADING, mapper.toExportCounts(body));
        return ResponseEntity.ok(toResponse(job));
    }

    @PutMapping(path = "/{jobId}/file", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> receiveFile(@PathVariable String jobId, HttpServletRequest request) {
        String existingJobId = migrationService.job(jobId).getId();
        importUploadReceiver.receive(existingJobId, request.getContentLengthLong(), request::getInputStream);
        return ResponseEntity.accepted().build();
    }

    @PostMapping(path = "/{jobId}/end", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportJobResponse> end(@PathVariable String jobId, @RequestBody EndImportJobRequest body,
                                                 HttpServletRequest request) {
        MigrationStatus job = migrationService.end(
            RequestUtil.getRequestServer(request),
            jobId,
            mapper.toEnding(body.getReason()),
            body.hasDetail() ? body.getDetail() : null,
            clientIpResolver.resolve(request));
        return ResponseEntity.ok(toResponse(job));
    }

    private ImportJobResponse toResponse(MigrationStatus job) {
        return mapper.toJobResponse(job, migrationConfiguration.maxUploadBytes());
    }
}
