package gg.modl.backend.instance.setup;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.web.ForwardedHeaders;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import gg.modl.proto.modl.v1.CheckPublicUrlRequest;
import gg.modl.proto.modl.v1.CheckPublicUrlResponse;
import gg.modl.proto.modl.v1.SetupWizardState;
import gg.modl.proto.modl.v1.UpdateSetupStepRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETUP)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelSetupController {
    private final SetupProgressService setupProgressService;
    private final PublicUrlProbe publicUrlProbe;

    @GetMapping
    public SetupWizardState state(HttpServletRequest request) {
        return SetupProtoMapper.toWizardState(setupProgressService.state(ForwardedHeaders.present(request)));
    }

    @PutMapping("/steps/{step}")
    public SetupWizardState updateStep(@PathVariable String step, @RequestBody UpdateSetupStepRequest body, HttpServletRequest request) {
        SetupStep setupStep = SetupStep.fromPathName(step)
            .orElseThrow(() -> new ValidationException("Unknown setup step"));
        SetupStepStatus status = SetupProtoMapper.fromProto(body.getStatus())
            .orElseThrow(() -> new ValidationException("A step can only be marked done"));
        return SetupProtoMapper.toWizardState(setupProgressService.updateStep(setupStep, status, ForwardedHeaders.present(request)));
    }

    @PostMapping("/public-url/check")
    public CheckPublicUrlResponse checkPublicUrl(@RequestBody CheckPublicUrlRequest body) {
        return SetupProtoMapper.toCheckResponse(publicUrlProbe.check(body.getUrl()));
    }
}
