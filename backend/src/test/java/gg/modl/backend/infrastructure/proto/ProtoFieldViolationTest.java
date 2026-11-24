package gg.modl.backend.infrastructure.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import build.buf.validate.FieldPath;
import build.buf.validate.FieldPathElement;
import build.buf.validate.Violation;
import org.junit.jupiter.api.Test;

class ProtoFieldViolationTest {

    @Test
    void rendersNestedFieldPathWithSubscripts() {
        Violation violation = Violation.newBuilder()
            .setField(FieldPath.newBuilder()
                .addElements(FieldPathElement.newBuilder().setFieldName("chat_messages").setIndex(2))
                .addElements(FieldPathElement.newBuilder().setFieldName("labels").setStringKey("a\"b"))
                .addElements(FieldPathElement.newBuilder().setFieldName("content")))
            .setMessage("value length must be at most 256 characters")
            .build();

        ProtoFieldViolation rendered = ProtoFieldViolation.from(violation);

        assertEquals("chat_messages[2].labels[\"a\\\"b\"].content", rendered.fieldPath());
        assertEquals("value length must be at most 256 characters", rendered.message());
    }

    @Test
    void rendersUnsignedKeysWithoutSignOverflow() {
        Violation violation = Violation.newBuilder()
            .setField(FieldPath.newBuilder()
                .addElements(FieldPathElement.newBuilder().setFieldName("weights").setUintKey(-1L)))
            .setMessage("invalid")
            .build();

        assertEquals("weights[18446744073709551615]", ProtoFieldViolation.from(violation).fieldPath());
    }
}
