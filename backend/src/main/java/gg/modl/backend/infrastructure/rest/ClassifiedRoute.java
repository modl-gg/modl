package gg.modl.backend.infrastructure.rest;

public record ClassifiedRoute(String path, RouteGroup group, ApiVersion apiVersion) {

    public boolean isApi() {
        return group != RouteGroup.STATIC_CONTENT;
    }

    public boolean isPanelArea() {
        return group == RouteGroup.PANEL || group == RouteGroup.PANEL_AUTH;
    }

    public boolean requiresPanelPermission() {
        return group == RouteGroup.PANEL;
    }

    public boolean isPanelOrPublic() {
        return isPanelArea() || group == RouteGroup.PUBLIC;
    }

    public boolean isMinecraftApi() {
        return group == RouteGroup.MINECRAFT;
    }

    public boolean isStorageObject() {
        return group == RouteGroup.STORAGE_OBJECT;
    }

    public boolean isOperational() {
        return group == RouteGroup.HEALTH || group == RouteGroup.ACTUATOR;
    }

    public boolean isChildOf(String prefix) {
        return path.startsWith(prefix + "/");
    }
}
