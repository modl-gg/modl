package gg.modl.minecraft.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import gg.modl.minecraft.core.boot.LoginGate;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class VelocityLoginGateListener {
    private final LoginGate gate;

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event) {
        if (gate.isOpen()) return;
        event.setResult(ResultedEvent.ComponentResult.denied(Colors.get(gate.deniedMessage())));
    }
}
