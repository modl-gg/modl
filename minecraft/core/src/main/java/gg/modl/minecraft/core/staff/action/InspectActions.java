package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.commands.staff.ProfileMenuOpener;
import gg.modl.minecraft.core.impl.menus.inspect.InspectMenu;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

@RequiredArgsConstructor
public final class InspectActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirements.VIEW_PLAYERS;

    private final StaffActionContext context;
    private final HttpClientHolder httpClientHolder;

    public void openMenu(StaffActor actor, String playerQuery) {
        Platform platform = context.getPlatform();
        UUID viewer = actor.uuid();
        ProfileMenuOpener.openProfileMenu(actor.commandActor(), httpClientHolder.getClient(), platform,
                context.getCache(), context.getLocaleManager(), playerQuery,
                (profileResponse, viewerName, wrapper) -> {
                    InspectContext inspectContext = new InspectContext(profileResponse.getProfile(),
                            profileResponse.getPunishmentCount(), profileResponse.getNoteCount());
                    new InspectMenu(platform, httpClientHolder.getClient(), viewer, viewerName,
                            profileResponse.getProfile(), null, inspectContext).display(wrapper);
                },
                this::logFailure);
    }

    public void logFailure(String playerQuery, Throwable throwable) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        PluginLogger logger = context.getPlatform().getLogger();
        logger.severe("Inspect lookup failed for " + playerQuery + " (" + root.getClass().getSimpleName() + ")"
                + (message != null && !message.isEmpty() ? ": " + message : ""));
        StringWriter stackTrace = new StringWriter();
        root.printStackTrace(new PrintWriter(stackTrace));
        logger.severe(stackTrace.toString());
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null ? current : new RuntimeException("Unknown inspect failure");
    }
}
