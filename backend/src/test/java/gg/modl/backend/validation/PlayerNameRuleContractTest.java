package gg.modl.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buf.validate.FieldRules;
import com.buf.validate.ValidateProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.proto.modl.v1.PlayerProto;
import gg.modl.proto.modl.v1.RealtimeProto;
import gg.modl.proto.modl.v1.TicketProto;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerNameRuleContractTest {
    private static final Pattern NAME = Pattern.compile(RegExpConstants.MINECRAFT_USERNAME);

    @Test
    void everyPlayerNameFieldInTheWireContractUsesTheOneSharedRule() {
        List<String> nameFields = new ArrayList<>();
        for (FileDescriptor file : List.of(PlayerProto.getDescriptor(), TicketProto.getDescriptor(), RealtimeProto.getDescriptor())) {
            for (Descriptor message : file.getMessageTypes()) {
                collect(message, nameFields);
            }
        }

        assertThat(nameFields).hasSizeGreaterThanOrEqualTo(7).allSatisfy(pattern ->
            assertThat(pattern.substring(pattern.indexOf(' ') + 1)).isEqualTo(RegExpConstants.MINECRAFT_USERNAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Steve", "a", "Notch_123", ".BedrockPlayer", "*Bedrock", "+Bedrock", ".A23456789012345"})
    void javaAndBedrockNamesAreAccepted(String name) {
        assertThat(NAME.matcher(name).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..Steve", "Steve.", "St eve", "Steve;op me", "@a", "Steve\n", "A2345678901234567",
        ".A234567890123456", "-Steve", "Ste\"ve", "Steve'", "Steve&", "Steve|"})
    void namesUnsafeForAConsoleCommandAreRefused(String name) {
        assertThat(NAME.matcher(name).matches()).isFalse();
    }

    private static void collect(Descriptor message, List<String> nameFields) {
        for (FieldDescriptor field : message.getFields()) {
            if (field.getType() != FieldDescriptor.Type.STRING) {
                continue;
            }
            FieldRules rules = field.getOptions().getExtension(ValidateProto.field);
            String pattern = rules.getString().getPattern();
            boolean playerName = field.getName().equals("username") || field.getName().endsWith("_name");
            if (playerName && !pattern.isEmpty()) {
                nameFields.add(message.getName() + "." + field.getName() + " " + pattern);
            }
        }
        message.getNestedTypes().forEach(nested -> collect(nested, nameFields));
    }
}
