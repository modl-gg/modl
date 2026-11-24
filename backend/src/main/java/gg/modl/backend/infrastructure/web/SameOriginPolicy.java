package gg.modl.backend.infrastructure.web;

import gg.modl.backend.instance.PublicUrls;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SameOriginPolicy {
    private final PublicUrls publicUrls;

    public boolean isTrustedOrigin(@Nullable String originOrUrl, HttpOrigin requestOrigin) {
        return HttpOrigin.parse(originOrUrl)
            .filter(origin -> origin.equals(requestOrigin) || origin.equals(publicOrigin()))
            .isPresent();
    }

    public HttpOrigin publicOrigin() {
        return publicUrls.publicOrigin();
    }
}
