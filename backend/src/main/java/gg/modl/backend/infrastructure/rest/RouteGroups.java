package gg.modl.backend.infrastructure.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

public final class RouteGroups {
    private static final String SEPARATOR = "/";
    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";
    private static final String VERSION_1 = "v1";
    private static final String VERSION_2 = "v2";
    private static final String VERSION_3 = "v3";
    private static final String ACTUATOR = "actuator";
    private static final String PANEL = "panel";
    private static final String AUTH = "auth";
    private static final String PUBLIC = "public";
    private static final String MINECRAFT = "minecraft";
    private static final String STORAGE = "storage";
    private static final String OBJECTS = "objects";
    private static final String REALTIME = "realtime";
    private static final String WEBSOCKET = "ws";
    private static final String HEALTH = "health";
    private static final String CLASSIFICATION_ATTRIBUTE = RouteGroups.class.getName() + ".classification";

    private RouteGroups() {
    }

    public static ClassifiedRoute classify(HttpServletRequest request) {
        String requestUri = request.getRequestURI() == null ? SEPARATOR : request.getRequestURI();
        if (request.getAttribute(CLASSIFICATION_ATTRIBUTE) instanceof RequestClassification cached
            && cached.requestUri().equals(requestUri)) {
            return cached.route();
        }
        ClassifiedRoute route = classifyUri(requestUri, request.getContextPath());
        request.setAttribute(CLASSIFICATION_ATTRIBUTE, new RequestClassification(requestUri, route));
        return route;
    }

    private static ClassifiedRoute classifyUri(String requestUri, String contextPath) {
        if (contextPath == null || contextPath.isEmpty() || !requestUri.startsWith(contextPath)) {
            return classifyPath(requestUri);
        }
        try {
            return classify(RequestPath.parse(requestUri, contextPath).pathWithinApplication());
        } catch (IllegalArgumentException malformed) {
            return malformed(requestUri);
        }
    }

    public static ClassifiedRoute classifyPath(String rawPath) {
        String absolutePath = rawPath == null || !rawPath.startsWith(SEPARATOR) ? SEPARATOR + nullToEmpty(rawPath) : rawPath;
        try {
            return classify(PathContainer.parsePath(absolutePath));
        } catch (IllegalArgumentException malformed) {
            return malformed(absolutePath);
        }
    }

    private static ClassifiedRoute malformed(String rawPath) {
        return new ClassifiedRoute(rawPath, RouteGroup.MALFORMED, ApiVersion.NONE);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ClassifiedRoute classify(PathContainer path) {
        List<String> segments = decodedSegments(path);
        String normalized = SEPARATOR + String.join(SEPARATOR, segments);
        if (segments.contains(CURRENT_SEGMENT) || segments.contains(PARENT_SEGMENT)) {
            return malformed(normalized);
        }
        ApiVersion version = apiVersion(segments);
        return new ClassifiedRoute(normalized, group(segments, version), version);
    }

    private static List<String> decodedSegments(PathContainer path) {
        List<String> segments = new ArrayList<>();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                for (String part : segment.valueToMatch().split(SEPARATOR)) {
                    if (!part.isEmpty()) {
                        segments.add(part);
                    }
                }
            }
        }
        return segments;
    }

    private static ApiVersion apiVersion(List<String> segments) {
        String first = segment(segments, 0);
        return switch (first) {
            case VERSION_1 -> ApiVersion.V1;
            case VERSION_2 -> ApiVersion.V2;
            case VERSION_3 -> ApiVersion.V3;
            default -> ApiVersion.NONE;
        };
    }

    private static RouteGroup group(List<String> segments, ApiVersion version) {
        return switch (version) {
            case V1 -> versionOneGroup(segments);
            case V2, V3 -> MINECRAFT.equals(segment(segments, 1)) ? RouteGroup.MINECRAFT : RouteGroup.OTHER_API;
            case NONE -> ACTUATOR.equals(segment(segments, 0)) ? RouteGroup.ACTUATOR : RouteGroup.STATIC_CONTENT;
        };
    }

    private static RouteGroup versionOneGroup(List<String> segments) {
        String area = segment(segments, 1);
        return switch (area) {
            case PANEL -> AUTH.equals(segment(segments, 2)) ? RouteGroup.PANEL_AUTH : RouteGroup.PANEL;
            case PUBLIC -> RouteGroup.PUBLIC;
            case MINECRAFT -> RouteGroup.MINECRAFT;
            case STORAGE -> OBJECTS.equals(segment(segments, 2)) && segments.size() > 3
                ? RouteGroup.STORAGE_OBJECT
                : RouteGroup.OTHER_API;
            case REALTIME -> WEBSOCKET.equals(segment(segments, 2)) && segments.size() == 3
                ? RouteGroup.REALTIME
                : RouteGroup.OTHER_API;
            case HEALTH -> segments.size() == 2 ? RouteGroup.HEALTH : RouteGroup.OTHER_API;
            default -> RouteGroup.OTHER_API;
        };
    }

    private static String segment(List<String> segments, int index) {
        return index < segments.size() ? segments.get(index) : "";
    }

    private record RequestClassification(String requestUri, ClassifiedRoute route) {
    }
}
