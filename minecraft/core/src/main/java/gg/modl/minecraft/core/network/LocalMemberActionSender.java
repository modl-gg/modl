package gg.modl.minecraft.core.network;

import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class LocalMemberActionSender implements MemberActionSender {
    private final MemberActionExecutor executor;
    private final Supplier<String> serverName;

    @Override
    public void send(MemberAction action, Consumer<MemberActionOutcome> onResult) {
        executor.submit(action.stampedBy(serverName.get(), null, false), ActionOrigin.LOCAL, onResult);
    }
}
