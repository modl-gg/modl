package gg.modl.backend.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

final class MailFailures {
    private static final int MAX_DETAIL_LENGTH = 300;
    private static final int MAX_CHAIN_LENGTH = 10;

    private MailFailures() {
    }

    static MailFailure classify(Exception failure, Supplier<MailDeliveryOutcome> untypedOutcome) {
        return typed(failure).orElseGet(() -> new MailFailure(untypedOutcome.get(), detail(failure)));
    }

    static Optional<MailFailure> typed(Exception failure) {
        List<Throwable> chain = causeChain(failure);
        return typedOutcome(chain).map(outcome -> new MailFailure(outcome, detail(chain)));
    }

    static String detail(Exception failure) {
        return detail(causeChain(failure));
    }

    private static Optional<MailDeliveryOutcome> typedOutcome(List<Throwable> chain) {
        if (anyInstanceOf(chain, AuthenticationFailedException.class, MailAuthenticationException.class)) {
            return Optional.of(MailDeliveryOutcome.AUTH_FAILED);
        }
        if (anyInstanceOf(chain, SSLException.class)) {
            return Optional.of(MailDeliveryOutcome.TLS_FAILED);
        }
        if (anyInstanceOf(chain, SocketTimeoutException.class)) {
            return Optional.of(MailDeliveryOutcome.TIMED_OUT);
        }
        if (anyInstanceOf(chain, ConnectException.class, UnknownHostException.class, NoRouteToHostException.class)) {
            return Optional.of(MailDeliveryOutcome.CONNECT_FAILED);
        }
        if (anyInstanceOf(chain, SendFailedException.class)) {
            return Optional.of(MailDeliveryOutcome.REJECTED);
        }
        return Optional.empty();
    }

    private static boolean anyInstanceOf(List<Throwable> chain, Class<?>... types) {
        return chain.stream().anyMatch(cause -> Arrays.stream(types).anyMatch(type -> type.isInstance(cause)));
    }

    private static String detail(List<Throwable> chain) {
        Throwable root = chain.getLast();
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String singleLine = message.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.length() <= MAX_DETAIL_LENGTH ? singleLine : singleLine.substring(0, MAX_DETAIL_LENGTH);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>(List.of(failure));
        while (!pending.isEmpty() && chain.size() < MAX_CHAIN_LENGTH) {
            Throwable current = pending.removeFirst();
            if (chain.contains(current)) {
                continue;
            }
            chain.add(current);
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            if (current instanceof MailSendException sendFailure) {
                pending.addAll(Arrays.asList(sendFailure.getMessageExceptions()));
            }
        }
        return chain;
    }
}
