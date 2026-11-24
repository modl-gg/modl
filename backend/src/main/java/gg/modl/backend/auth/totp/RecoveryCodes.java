package gg.modl.backend.auth.totp;

import gg.modl.backend.infrastructure.onetimecode.HumanCode;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecoveryCodes {
    public static final int COUNT = 10;
    static final int LENGTH = 12;
    private static final Pattern SHAPE = Pattern.compile("[A-HJ-NP-Z2-9]{" + LENGTH + "}");

    private final OneTimeCodeCodec oneTimeCodeCodec;

    public List<String> generate() {
        return IntStream.range(0, COUNT).mapToObj(ignored -> HumanCode.format(HumanCode.generate(LENGTH))).toList();
    }

    public List<String> hashAll(List<String> codes) {
        return codes.stream().map(this::hash).toList();
    }

    public String hash(String code) {
        return oneTimeCodeCodec.hash(HumanCode.normalize(code));
    }

    public static boolean looksLikeRecoveryCode(String code) {
        return SHAPE.matcher(HumanCode.normalize(code)).matches();
    }
}
