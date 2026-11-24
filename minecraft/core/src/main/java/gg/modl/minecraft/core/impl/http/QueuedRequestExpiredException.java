package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.api.http.PanelUnavailableException;

final class QueuedRequestExpiredException extends PanelUnavailableException {
    QueuedRequestExpiredException(String endpoint, String message) {
        super(endpoint, AbstractModlHttpTransport.STATUS_UNREACHABLE, message);
    }
}
