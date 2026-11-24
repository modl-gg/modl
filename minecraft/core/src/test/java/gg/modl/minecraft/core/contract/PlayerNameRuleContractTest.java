package gg.modl.minecraft.core.contract;

import com.buf.validate.ValidateProto;
import com.google.protobuf.Descriptors.FieldDescriptor;
import gg.modl.minecraft.api.PlayerNames;
import gg.modl.proto.modl.v1.ChatLogEntry;
import gg.modl.proto.modl.v1.CommandLogEntry;
import gg.modl.proto.modl.v1.MinecraftClaimTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerNameRuleContractTest {
    @Test
    void thePluginNameRuleIsTheOneInTheWireContract() {
        assertEquals(PlayerNames.PATTERN, patternOf(ChatLogEntry.getDescriptor().findFieldByName("username")));
        assertEquals(PlayerNames.PATTERN, patternOf(CommandLogEntry.getDescriptor().findFieldByName("username")));
        assertEquals(PlayerNames.PATTERN, patternOf(MinecraftCreateTicketRequest.getDescriptor().findFieldByName("creator_name")));
        assertEquals(PlayerNames.PATTERN, patternOf(MinecraftClaimTicketRequest.getDescriptor().findFieldByName("player_name")));
    }

    private static String patternOf(FieldDescriptor field) {
        return field.getOptions().getExtension(ValidateProto.field).getString().getPattern();
    }
}
