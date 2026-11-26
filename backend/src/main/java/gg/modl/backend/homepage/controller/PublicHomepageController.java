package gg.modl.backend.homepage.controller;

import gg.modl.backend.homepage.service.HomepageSettingsService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.proto.modl.v1.PublicHomepageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_HOMEPAGE)
@RequiredArgsConstructor
public class PublicHomepageController {
    private final HomepageSettingsService settings;

    @GetMapping
    public PublicHomepageResponse get() {
        return HomepageSettingsProtoMapper.toPublicResponse(settings.published());
    }
}
