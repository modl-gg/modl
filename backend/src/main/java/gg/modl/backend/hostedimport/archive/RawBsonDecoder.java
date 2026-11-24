package gg.modl.backend.hostedimport.archive;

import org.jspecify.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RawBsonDecoder {
    private static final int MIN_DOCUMENT = 5;
    private static final int OBJECT_ID_BYTES = 12;
    private static final int OLD_BINARY = 2;

    private final ByteBuffer buffer;
    private final DocumentBudget budget;

    private RawBsonDecoder(byte[] bytes, DocumentBudget budget) {
        this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        this.budget = budget;
    }

    static Map<String, @Nullable Object> decode(byte[] bytes, DocumentBudget budget) {
        if (bytes.length < MIN_DOCUMENT || bytes.length > ArchiveLimits.MAX_BSON_DOCUMENT_BYTES) {
            throw unreadable("BSON document size");
        }
        RawBsonDecoder decoder = new RawBsonDecoder(bytes, budget);
        if (decoder.buffer.getInt(0) != bytes.length) {
            throw unreadable("BSON length does not match");
        }
        return decoder.document(bytes.length, false);
    }

    public static Map<String, @Nullable Object> decode(byte[] bytes) {
        return decode(bytes, new DocumentBudget(ArchiveLimits.MAX_DOCUMENT_HEAP_BYTES));
    }

    private Map<String, @Nullable Object> document(int limit, boolean asArray) {
        int start = buffer.position();
        int length = int32(limit);
        int end = start + length;
        if (length < MIN_DOCUMENT || length > limit - start) {
            throw unreadable("nested length out of bounds");
        }
        Map<String, @Nullable Object> fields = new LinkedHashMap<>();
        while (true) {
            require(end);
            byte type = buffer.get();
            if (type == 0) {
                if (buffer.position() != end) {
                    throw unreadable("terminator before the declared end");
                }
                return fields;
            }
            String name = budget.string(cstring(end));
            budget.enter(asArray ? "[]" : name);
            @Nullable Object value = value(type, end);
            budget.leave();
            if (asArray || !ExtendedJsonDecoder.CLASS_HINT.equals(name)) {
                fields.put(name, value);
            }
        }
    }

    private @Nullable Object value(byte type, int end) {
        budget.node();
        return switch (type) {
            case 0x01 -> {
                require(end, 8);
                yield buffer.getDouble();
            }
            case 0x02, 0x0D, 0x0E -> budget.string(string(end));
            case 0x03 -> document(end, false);
            case 0x04 -> array(end);
            case 0x05 -> binary(end);
            case 0x06, 0x0A -> null;
            case 0x07 -> objectId(end);
            case 0x08 -> bool(end);
            case 0x09 -> {
                require(end, 8);
                yield Instant.ofEpochMilli(buffer.getLong());
            }
            case 0x0B -> regex(end);
            case 0x0C -> dbPointer(end);
            case 0x0F -> codeWithScope(end);
            case 0x10 -> int32(end);
            case 0x11 -> timestamp(end);
            case 0x12 -> {
                require(end, 8);
                yield buffer.getLong();
            }
            case 0x13 -> decimal128(end);
            case (byte) 0xFF -> "MinKey";
            case 0x7F -> "MaxKey";
            default -> throw unreadable("unknown BSON type");
        };
    }

    private Object array(int end) {
        boolean fold = budget.foldsDates();
        Map<String, @Nullable Object> elements = document(end, true);
        if (fold) {
            FoldedDates folded = FoldedDates.EMPTY;
            for (@Nullable Object element : elements.values()) {
                if (element instanceof Instant date) {
                    folded = folded.with(date);
                }
            }
            return folded;
        }
        return new ArrayList<>(elements.values());
    }

    private BinaryValue binary(int end) {
        int length = int32(end);
        require(end, 1);
        int subtype = Byte.toUnsignedInt(buffer.get());
        if (length < 0) {
            throw unreadable("negative binary length");
        }
        require(end, length);
        if (subtype == OLD_BINARY) {
            if (length < 4) {
                throw unreadable("old binary too short");
            }
            int inner = int32(end);
            if (inner != length - 4) {
                throw unreadable("old binary length mismatch");
            }
            length = inner;
        }
        budget.bytes(length);
        byte[] data = new byte[length];
        buffer.get(data);
        return new BinaryValue(subtype, data);
    }

    private ObjectIdValue objectId(int end) {
        require(end, OBJECT_ID_BYTES);
        byte[] id = new byte[OBJECT_ID_BYTES];
        buffer.get(id);
        return ObjectIdValue.of(id);
    }

    private Boolean bool(int end) {
        require(end, 1);
        byte value = buffer.get();
        if (value != 0 && value != 1) {
            throw unreadable("boolean out of range");
        }
        return value == 1;
    }

    private Map<String, @Nullable Object> regex(int end) {
        Map<String, @Nullable Object> regex = new LinkedHashMap<>();
        regex.put("pattern", budget.string(cstring(end)));
        regex.put("options", budget.string(cstring(end)));
        return regex;
    }

    private Map<String, @Nullable Object> dbPointer(int end) {
        Map<String, @Nullable Object> pointer = new LinkedHashMap<>();
        pointer.put("ref", budget.string(string(end)));
        pointer.put("id", objectId(end));
        return pointer;
    }

    private Map<String, @Nullable Object> codeWithScope(int end) {
        int start = buffer.position();
        int length = int32(end);
        int scopeEnd = start + length;
        if (length < 14 || length > end - start) {
            throw unreadable("code with scope length");
        }
        Map<String, @Nullable Object> code = new LinkedHashMap<>();
        code.put("code", budget.string(string(scopeEnd)));
        code.put("scope", document(scopeEnd, false));
        if (buffer.position() != scopeEnd) {
            throw unreadable("code with scope length");
        }
        return code;
    }

    private Map<String, @Nullable Object> timestamp(int end) {
        require(end, 8);
        long increment = Integer.toUnsignedLong(buffer.getInt());
        long seconds = Integer.toUnsignedLong(buffer.getInt());
        Map<String, @Nullable Object> timestamp = new LinkedHashMap<>();
        timestamp.put("t", seconds);
        timestamp.put("i", increment);
        return timestamp;
    }

    private String decimal128(int end) {
        require(end, 16);
        long low = buffer.getLong();
        long high = buffer.getLong();
        return Decimal128Text.of(high, low);
    }

    private int int32(int end) {
        require(end, 4);
        return buffer.getInt();
    }

    private String string(int end) {
        int length = int32(end);
        if (length < 1) {
            throw unreadable("string length");
        }
        require(end, length);
        int start = buffer.position();
        if (buffer.get(start + length - 1) != 0) {
            throw unreadable("string terminator");
        }
        String value = utf8(start, length - 1);
        buffer.position(start + length);
        return value;
    }

    private String cstring(int end) {
        int start = buffer.position();
        int position = start;
        while (true) {
            if (position >= end) {
                throw unreadable("unterminated name");
            }
            if (buffer.get(position) == 0) {
                break;
            }
            position++;
        }
        String value = utf8(start, position - start);
        buffer.position(position + 1);
        return value;
    }

    private String utf8(int start, int length) {
        byte[] bytes = Arrays.copyOfRange(buffer.array(), start, start + length);
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw unreadable("invalid UTF-8");
        }
    }

    private void require(int end) {
        require(end, 1);
    }

    private void require(int end, int bytes) {
        if (bytes < 0 || buffer.position() + (long) bytes > end) {
            throw unreadable("value runs past its document");
        }
    }

    private static UnreadableDocumentException unreadable(String reason) {
        return new UnreadableDocumentException(reason, false);
    }

    static final class Decimal128Text {
        private static final int EXPONENT_BIAS = 6176;
        private static final BigInteger MAX_COEFFICIENT = BigInteger.TEN.pow(34).subtract(BigInteger.ONE);

        private Decimal128Text() {
        }

        static String of(long high, long low) {
            boolean negative = high < 0;
            long combination = (high >>> 58) & 0x1F;
            if (combination == 0x1F) {
                return "NaN";
            }
            if (combination == 0x1E) {
                return negative ? "-Infinity" : "Infinity";
            }
            int exponent;
            BigInteger coefficient;
            if (((high >>> 61) & 0x3) == 0x3) {
                exponent = (int) ((high >>> 47) & 0x3FFF);
                coefficient = BigInteger.ZERO;
            } else {
                exponent = (int) ((high >>> 49) & 0x3FFF);
                BigInteger upper = BigInteger.valueOf(high & 0x1FFFFFFFFFFFFL).shiftLeft(64);
                coefficient = upper.or(new BigInteger(Long.toUnsignedString(low)));
                if (coefficient.compareTo(MAX_COEFFICIENT) > 0) {
                    coefficient = BigInteger.ZERO;
                }
            }
            BigDecimal value = new BigDecimal(coefficient, EXPONENT_BIAS - exponent);
            String text = value.toString();
            return negative ? "-" + text : text;
        }
    }
}
