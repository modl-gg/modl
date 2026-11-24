package gg.modl.minecraft.spigot.chat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyVerdictMessageTest {
    @Test
    void aCancelCarriesItsChannelAndText() throws IOException {
        Optional<ProxyVerdictMessage> denial = ProxyVerdictMessage.denialIn(payload("COMMAND_RESULT", "CANCEL", "msg Steve"));

        assertTrue(denial.isPresent());
        assertEquals(ProxyVerdictChannel.COMMAND, denial.get().channel());
        assertEquals("msg Steve", denial.get().deniedText());
    }

    @Test
    void anOlderProxyWithoutTheTextStillDenies() throws IOException {
        Optional<ProxyVerdictMessage> denial = ProxyVerdictMessage.denialIn(payload("CHAT_RESULT", "CANCEL", null));

        assertTrue(denial.isPresent());
        assertNull(denial.get().deniedText());
    }

    @Test
    void allowedModifiedAndMalformedPayloadsAreNoDenial() throws IOException {
        assertFalse(ProxyVerdictMessage.denialIn(payload("CHAT_RESULT", "ALLOWED", null)).isPresent());
        assertFalse(ProxyVerdictMessage.denialIn(payload("CHAT_RESULT", "MODIFY", "changed")).isPresent());
        assertFalse(ProxyVerdictMessage.denialIn(payload("OTHER", "CANCEL", null)).isPresent());
        assertFalse(ProxyVerdictMessage.denialIn(new byte[] {1, 2}).isPresent());
    }

    private static byte[] payload(String queue, String result, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(UUID.randomUUID().toString());
        out.writeUTF(queue);
        out.writeUTF(result);
        if (text != null) out.writeUTF(text);
        return bytes.toByteArray();
    }
}
