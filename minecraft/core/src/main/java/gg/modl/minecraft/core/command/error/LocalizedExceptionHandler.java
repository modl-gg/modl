package gg.modl.minecraft.core.command.error;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.exception.CommandExceptionHandler;
import revxrsal.commands.exception.CommandInvocationException;
import revxrsal.commands.exception.SendableException;
import revxrsal.commands.exception.context.ErrorContext;

@RequiredArgsConstructor
final class LocalizedExceptionHandler<A extends CommandActor> implements CommandExceptionHandler<A> {
    private final CommandErrorCatalog catalog;
    private final CommandErrorReplies replies;
    private final PluginLogger logger;

    @Override
    public void handleException(Throwable failure, ErrorContext<A> context) {
        if (failure instanceof CommandInvocationException) {
            reportFailedCommand((CommandInvocationException) failure, context);
        } else if (carriesOwnMessage(failure)) {
            ((SendableException) failure).sendTo(context.actor());
        } else {
            replies.send(context.actor(), catalog.errorFor(failure));
        }
    }

    private static boolean carriesOwnMessage(Throwable failure) {
        return failure instanceof SendableException && !(failure instanceof CommandErrorException);
    }

    private void reportFailedCommand(CommandInvocationException failure, ErrorContext<A> context) {
        logger.warning("[commands] /" + context.context().command().firstNode().name() + " failed", failure.cause());
        replies.send(context.actor(), CommandError.of("failed"));
    }
}
