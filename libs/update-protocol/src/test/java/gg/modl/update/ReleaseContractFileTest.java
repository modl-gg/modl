package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseContractFileTest {
    @TempDir
    Path temp;

    @Test
    void theReleaseContractCarriesTheNumbersTheHostsCheck() throws IOException {
        Path output = temp.resolve("dist").resolve(ReleaseContractFile.FILE_NAME);

        ReleaseContractFile.main(new String[] {output.toString()});

        Map<?, ?> contract = new Gson().fromJson(new String(Files.readAllBytes(output), StandardCharsets.UTF_8), Map.class);
        assertEquals(EmbeddedUpdateContract.MIN_JAVA, ((Number) contract.get("minJava")).intValue());
        assertEquals(EmbeddedUpdateContract.LAUNCHER_API, ((Number) contract.get("launcherApi")).intValue());
        assertEquals(EmbeddedUpdateContract.HOST_API, ((Number) contract.get("embeddedHostApi")).intValue());
        assertEquals(3, contract.size());
    }
}
