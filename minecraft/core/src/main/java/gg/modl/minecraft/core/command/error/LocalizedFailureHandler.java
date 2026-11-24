package gg.modl.minecraft.core.command.error;

import lombok.RequiredArgsConstructor;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;
import revxrsal.commands.command.Potential;
import revxrsal.commands.exception.ExpectedLiteralException;
import revxrsal.commands.node.FailureHandler;
import revxrsal.commands.stream.StringStream;
import revxrsal.commands.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
final class LocalizedFailureHandler<A extends CommandActor> implements FailureHandler<A> {
    private static final int MAX_SUGGESTIONS = 6;

    private final CommandErrorReplies replies;
    private final FailureHandler<A> singleFailureHandler;

    @Override
    public void handleFailedAttempts(A actor, List<Potential<A>> attempts, StringStream input) {
        List<SubcommandChoice<A>> choices = subcommandChoices(actor, attempts);
        if (choices.size() < 2) {
            singleFailureHandler.handleFailedAttempts(actor, attempts, input);
            return;
        }
        suggestSubcommands(actor, choices, input);
    }

    private List<SubcommandChoice<A>> subcommandChoices(A actor, List<Potential<A>> attempts) {
        int furthest = 0;
        for (Potential<A> attempt : attempts) furthest = Math.max(furthest, attempt.failurePosition());
        List<SubcommandChoice<A>> choices = new ArrayList<>();
        for (Potential<A> attempt : attempts) {
            if (attempt.failurePosition() != furthest) continue;
            Throwable error = attempt.error();
            if (!(error instanceof ExpectedLiteralException)) return Collections.emptyList();
            ExecutableCommand<A> command = attempt.context().command();
            if (isVisibleTo(command, actor)) choices.add(new SubcommandChoice<>((ExpectedLiteralException) error, command));
        }
        return Collections.unmodifiableList(choices);
    }

    private void suggestSubcommands(A actor, List<SubcommandChoice<A>> choices, StringStream input) {
        Map<String, String> pathByLiteral = new LinkedHashMap<>();
        for (SubcommandChoice<A> choice : choices) {
            pathByLiteral.putIfAbsent(choice.failure.node().name(), choice.command.path());
        }
        String typed = choices.get(0).failure.input();
        List<String> matches = Strings.closestMatches(typed, pathByLiteral.keySet(), MAX_SUGGESTIONS);
        Collection<String> suggested = matches.isEmpty() ? pathByLiteral.keySet() : matches;

        replies.send(actor, CommandError.of("unknown_subcommand", mapOf("input", input.source())));
        for (String literal : suggested.stream().limit(MAX_SUGGESTIONS).collect(Collectors.toList())) {
            String path = pathByLiteral.get(literal);
            if (path != null) replies.send(actor, CommandError.of("suggestion", mapOf("command", path)));
        }
    }

    private boolean isVisibleTo(ExecutableCommand<A> command, A actor) {
        return !command.isSecret() && command.permission().isExecutableBy(actor);
    }

    @RequiredArgsConstructor
    private static final class SubcommandChoice<A extends CommandActor> {
        private final ExpectedLiteralException failure;
        private final ExecutableCommand<A> command;
    }
}
