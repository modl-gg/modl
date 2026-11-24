package gg.modl.backend.infrastructure.proto;

import build.buf.validate.FieldPath;
import build.buf.validate.FieldPathElement;
import build.buf.validate.Violation;

public record ProtoFieldViolation(String fieldPath, String message) {

    static ProtoFieldViolation from(Violation violation) {
        return new ProtoFieldViolation(render(violation.getField()), violation.getMessage());
    }

    private static String render(FieldPath path) {
        StringBuilder rendered = new StringBuilder();
        for (FieldPathElement element : path.getElementsList()) {
            if (!rendered.isEmpty()) {
                rendered.append('.');
            }
            rendered.append(element.getFieldName()).append(subscript(element));
        }
        return rendered.toString();
    }

    private static String subscript(FieldPathElement element) {
        return switch (element.getSubscriptCase()) {
            case INDEX -> "[" + element.getIndex() + "]";
            case BOOL_KEY -> "[" + element.getBoolKey() + "]";
            case INT_KEY -> "[" + element.getIntKey() + "]";
            case UINT_KEY -> "[" + Long.toUnsignedString(element.getUintKey()) + "]";
            case STRING_KEY -> "[\"" + element.getStringKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
            case SUBSCRIPT_NOT_SET -> "";
        };
    }
}
