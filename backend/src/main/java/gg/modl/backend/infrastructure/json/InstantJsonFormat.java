package gg.modl.backend.infrastructure.json;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

public final class InstantJsonFormat {
    public static final DateTimeFormatter WIRE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    private InstantJsonFormat() {
    }

    public static String format(Instant instant) {
        return WIRE_FORMAT.format(instant);
    }

    public static SimpleModule module() {
        return new SimpleModule("modl-instant-format").addSerializer(Instant.class, new MillisecondInstantSerializer());
    }

    private static final class MillisecondInstantSerializer extends StdSerializer<Instant> {
        private MillisecondInstantSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(format(value));
        }
    }
}
