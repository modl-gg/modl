package gg.modl.update;

import java.io.IOException;
import java.util.Map;

public interface JsonMaps {
    Map<String, Object> read(byte[] json) throws IOException;

    byte[] write(Map<String, Object> value) throws IOException;
}
