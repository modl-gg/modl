package gg.modl.backend.infrastructure.web;

import gg.modl.backend.infrastructure.rest.RouteGroups;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

public class SpaFallbackResourceResolver extends PathResourceResolver {
    private static final String INDEX_HTML = "index.html";

    @Override
    protected @Nullable Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource requested = super.getResource(resourcePath, location);
        if (requested != null) {
            return requested;
        }
        if (RouteGroups.classifyPath(resourcePath).isApi() || looksLikeFile(resourcePath)) {
            return null;
        }
        return super.getResource(INDEX_HTML, location);
    }

    private static boolean looksLikeFile(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        return resourcePath.indexOf('.', lastSlash + 1) >= 0;
    }
}
