package gg.modl.backend.ai.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class AiAddressPolicy {
    private static final List<Cidr> ALWAYS_BLOCKED_V4 = Cidr.all(
        "0.0.0.0/8", "224.0.0.0/4", "240.0.0.0/4", "169.254.0.0/16",
        "100.100.100.200/32", "168.63.129.16/32", "192.0.0.192/32");
    private static final List<Cidr> ALWAYS_BLOCKED_V6 = Cidr.all(
        "::/128", "ff00::/8", "fe80::/10", "fd00:ec2::254/128", "fd20:ce::254/128", "2001::/32");
    private static final List<Cidr> PLAIN_HTTP_V4 = Cidr.all(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10");
    private static final List<Cidr> PLAIN_HTTP_V6 = Cidr.all("::1/128", "fc00::/7");
    private static final Cidr MAPPED = Cidr.parse("::ffff:0:0/96");
    private static final Cidr COMPATIBLE = Cidr.parse("::/96");
    private static final Cidr NAT64 = Cidr.parse("64:ff9b::/96");
    private static final Cidr SIX_TO_FOUR = Cidr.parse("2002::/16");
    private static final Cidr LOOPBACK_OR_UNSPECIFIED_V6 = Cidr.parse("::/127");

    public Verdict check(InetAddress address, boolean plainHttp) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return checkV6(bytes, plainHttp);
        }
        return checkV4(bytes, plainHttp);
    }

    private Verdict checkV6(byte[] bytes, boolean plainHttp) {
        if (Cidr.contains(ALWAYS_BLOCKED_V6, bytes)) {
            return Verdict.BLOCKED;
        }
        byte[] embedded = embeddedIpv4(bytes);
        if (embedded != null) {
            return checkV4(embedded, plainHttp);
        }
        return !plainHttp || Cidr.contains(PLAIN_HTTP_V6, bytes) ? Verdict.ALLOWED : Verdict.PLAIN_HTTP_PUBLIC;
    }

    private Verdict checkV4(byte[] bytes, boolean plainHttp) {
        if (Cidr.contains(ALWAYS_BLOCKED_V4, bytes)) {
            return Verdict.BLOCKED;
        }
        return !plainHttp || Cidr.contains(PLAIN_HTTP_V4, bytes) ? Verdict.ALLOWED : Verdict.PLAIN_HTTP_PUBLIC;
    }

    private static byte @Nullable [] embeddedIpv4(byte[] bytes) {
        if (MAPPED.matches(bytes) || NAT64.matches(bytes)
            || (COMPATIBLE.matches(bytes) && !LOOPBACK_OR_UNSPECIFIED_V6.matches(bytes))) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        if (SIX_TO_FOUR.matches(bytes)) {
            return Arrays.copyOfRange(bytes, 2, 6);
        }
        return null;
    }

    public enum Verdict {
        ALLOWED,
        BLOCKED,
        PLAIN_HTTP_PUBLIC
    }

    private static final class Cidr {
        private final byte[] network;
        private final int prefixLength;

        private Cidr(byte[] network, int prefixLength) {
            this.network = network.clone();
            this.prefixLength = prefixLength;
        }

        static List<Cidr> all(String... blocks) {
            return Arrays.stream(blocks).map(Cidr::parse).toList();
        }

        static Cidr parse(String block) {
            int slash = block.indexOf('/');
            try {
                byte[] network = InetAddress.getByName(block.substring(0, slash)).getAddress();
                byte[] widened = network.length == 4 && block.contains(":") ? toV6(network) : network;
                return new Cidr(widened, Integer.parseInt(block.substring(slash + 1)));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid CIDR " + block, e);
            }
        }

        static boolean contains(List<Cidr> blocks, byte[] address) {
            return blocks.stream().anyMatch(block -> block.matches(address));
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static byte[] toV6(byte[] v4) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            System.arraycopy(v4, 0, mapped, 12, 4);
            return mapped;
        }
    }
}
