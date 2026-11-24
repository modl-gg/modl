package gg.modl.minecraft.spigot.chat;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyChatVerdictsTest {
    private final AtomicLong nanos = new AtomicLong();
    private final ProxyChatVerdicts verdicts = new ProxyChatVerdicts(nanos::get);
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void aDeniedMessageIsCancelledOnceAndOnlyForItsSender() {
        verdicts.deny(player, ProxyVerdictChannel.CHAT, "hello there");

        assertFalse(verdicts.consumeDenial(other, ProxyVerdictChannel.CHAT, "hello there"));
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "hello there"));
        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "hello there"));
    }

    @Test
    void anAllowedMessageIsNeverCancelledEvenAfterADenialOfAnotherText() {
        verdicts.deny(player, ProxyVerdictChannel.CHAT, "muted words");

        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "something else"));
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "muted words"));
    }

    @Test
    void whitespaceTheServerNormalisesStillMatches() {
        verdicts.deny(player, ProxyVerdictChannel.CHAT, "  hello    there ");

        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "hello there"));
    }

    @Test
    void commandsMatchWithOrWithoutTheirSlashAndOldClientsSendCommandsAsChat() {
        verdicts.deny(player, ProxyVerdictChannel.COMMAND, "msg Steve hi");
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.COMMAND, "/msg Steve hi"));

        verdicts.deny(player, ProxyVerdictChannel.CHAT, "/msg Steve hi");
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.COMMAND, "/msg Steve hi"));
    }

    @Test
    void chatAndCommandDenialsDoNotMix() {
        verdicts.deny(player, ProxyVerdictChannel.COMMAND, "spawn");

        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "spawn"));
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.COMMAND, "/spawn"));
    }

    @Test
    void aTruncatedLongMessageStillMatches() {
        String longText = String.join("", Collections.nCopies(30, "words "));
        verdicts.deny(player, ProxyVerdictChannel.CHAT, longText);

        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, longText.substring(0, 100)));
    }

    @Test
    void aDenialWithoutTextCoversTheNextMessage() {
        verdicts.deny(player, ProxyVerdictChannel.CHAT, null);

        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "anything"));
        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "anything"));
    }

    @Test
    void oldDenialsExpireAndForgettingAPlayerDropsThem() {
        verdicts.deny(player, ProxyVerdictChannel.CHAT, "late");
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(61));
        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "late"));

        verdicts.deny(player, ProxyVerdictChannel.CHAT, "left");
        verdicts.forget(player);
        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "left"));
    }

    @Test
    void theNumberOfPendingDenialsIsBounded() {
        for (int index = 0; index <= ProxyChatVerdicts.MAX_PENDING_PER_CHANNEL; index++) {
            verdicts.deny(player, ProxyVerdictChannel.CHAT, "message " + index);
        }

        assertFalse(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "message 0"));
        assertTrue(verdicts.consumeDenial(player, ProxyVerdictChannel.CHAT, "message " + ProxyChatVerdicts.MAX_PENDING_PER_CHANNEL));
    }
}
