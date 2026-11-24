package gg.modl.backend.ai.http;

import java.net.UnknownHostException;

public class AiBlockedAddressException extends UnknownHostException {
    public AiBlockedAddressException(String host) {
        super("The address of " + host + " is not allowed");
    }
}
