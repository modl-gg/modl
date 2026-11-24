package gg.modl.minecraft.spigot.chat;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ProxyChatVerdicts {
    static final int MAX_PENDING_PER_CHANNEL = 64;
    static final int SHORTEST_SERVER_TRUNCATION = 100;
    static final long DENIAL_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final LongSupplier nanoClock;
    private final Map<UUID, PendingDenials> players = new ConcurrentHashMap<>();

    public ProxyChatVerdicts() {
        this(System::nanoTime);
    }

    ProxyChatVerdicts(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    void deny(UUID player, ProxyVerdictChannel channel, @Nullable String text) {
        players.computeIfAbsent(player, ignored -> new PendingDenials()).add(channel, text, nanoClock.getAsLong());
    }

    boolean consumeDenial(UUID player, ProxyVerdictChannel channel, String eventText) {
        PendingDenials pending = players.get(player);
        if (pending == null) return false;
        long now = nanoClock.getAsLong();
        if (pending.consume(channel, eventText, now)) return true;
        return channel == ProxyVerdictChannel.COMMAND && eventText.startsWith("/")
                && pending.consume(ProxyVerdictChannel.CHAT, eventText, now);
    }

    void forget(UUID player) {
        players.remove(player);
    }

    void clear() {
        players.clear();
    }

    private static String significantText(String text) {
        StringBuilder significant = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character) || Character.isISOControl(character)) {
                continue;
            }
            if (significant.length() == 0 && character == '/') continue;
            significant.append(character);
        }
        return significant.toString();
    }

    private static final class PendingDenials {
        private final Map<ProxyVerdictChannel, Deque<Denial>> byChannel = new EnumMap<>(ProxyVerdictChannel.class);

        private synchronized void add(ProxyVerdictChannel channel, @Nullable String text, long now) {
            Deque<Denial> denials = byChannel.computeIfAbsent(channel, ignored -> new ArrayDeque<>());
            if (denials.size() >= MAX_PENDING_PER_CHANNEL) denials.pollFirst();
            denials.addLast(new Denial(text, now));
        }

        private synchronized boolean consume(ProxyVerdictChannel channel, String eventText, long now) {
            Deque<Denial> denials = byChannel.get(channel);
            if (denials == null) return false;
            for (Iterator<Denial> iterator = denials.iterator(); iterator.hasNext(); ) {
                Denial denial = iterator.next();
                if (now - denial.receivedAtNanos > DENIAL_LIFETIME_NANOS) {
                    iterator.remove();
                } else if (denial.covers(eventText)) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Denial {
        private final @Nullable String significant;
        private final int rawLength;
        private final long receivedAtNanos;

        private Denial(@Nullable String text, long receivedAtNanos) {
            this.significant = text == null ? null : significantText(text);
            this.rawLength = text == null ? 0 : text.length();
            this.receivedAtNanos = receivedAtNanos;
        }

        private boolean covers(String eventText) {
            String denied = significant;
            if (denied == null) return true;
            String event = significantText(eventText);
            if (event.equals(denied)) return true;
            boolean mayBeTruncated = rawLength >= SHORTEST_SERVER_TRUNCATION && eventText.length() >= SHORTEST_SERVER_TRUNCATION - 1;
            return mayBeTruncated && (denied.startsWith(event) || event.startsWith(denied));
        }
    }
}
