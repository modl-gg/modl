package io.github._4drian3d.signedvelocity.paper.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalExecutionDetectorTest {
    @Test
    void theChatHandlerAndItsLambdasMarkProxiedChat() {
        assertTrue(LocalExecutionDetector.isPlayerChatFrame("handleChat"));
        assertTrue(LocalExecutionDetector.isPlayerChatFrame("lambda$handleChat$13"));
    }

    @Test
    void chatPreviewFramesNeverWaitForAProxyVerdict() {
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("handleChatPreview"));
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("lambda$handleChatPreview$22"));
    }

    @Test
    void otherPacketHandlersSharingThePrefixAreLocal() {
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("chat"));
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("handleChatCommand"));
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("lambda$handleChatCommand$14"));
        assertFalse(LocalExecutionDetector.isPlayerChatFrame("handleChatAck"));
    }
}
