package gg.modl.minecraft.core.network;

import lombok.Value;

import java.util.UUID;

@Value
public class MemberActionResult {
    String requestId;
    MemberActionOutcome outcome;
    UUID actor;
}
