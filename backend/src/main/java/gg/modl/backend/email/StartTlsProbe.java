package gg.modl.backend.email;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartTlsProbe {
    static final Duration STEP_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_LINE_BYTES = 1000;
    static final int MAX_REPLY_LINES = 100;

    private static final int GREETING = 220;
    private static final int COMPLETED = 250;
    private static final String EXTENSION = "STARTTLS";
    private static final int CODE_DIGITS = 3;
    private static final char CONTINUATION = '-';
    private static final char LINE_FEED = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char SCOPE_SEPARATOR = '%';

    public enum Support {
        OFFERED,
        NOT_OFFERED,
        UNKNOWN
    }

    private final Duration stepTimeout;

    public StartTlsProbe() {
        this(STEP_TIMEOUT);
    }

    StartTlsProbe(Duration stepTimeout) {
        this.stepTimeout = stepTimeout;
    }

    public Support probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(stepTimeout.toMillis()));
            return converse(socket, new ReplyReader(socket, stepTimeout));
        } catch (IOException | IllegalArgumentException e) {
            log.debug("The mail server at {}:{} could not be asked whether it offers STARTTLS", host, port, e);
            return Support.UNKNOWN;
        }
    }

    private static Support converse(Socket socket, ReplyReader replies) throws IOException {
        OutputStream out = socket.getOutputStream();
        if (replies.next().code() != GREETING) {
            return Support.UNKNOWN;
        }
        send(out, "EHLO " + addressLiteral(socket.getLocalAddress()));
        Reply capabilities = replies.next();
        quit(out);
        if (capabilities.code() != COMPLETED) {
            return Support.UNKNOWN;
        }
        return capabilities.offers(EXTENSION) ? Support.OFFERED : Support.NOT_OFFERED;
    }

    private static void quit(OutputStream out) {
        try {
            send(out, "QUIT");
        } catch (IOException e) {
            log.debug("The mail server closed the connection before QUIT", e);
        }
    }

    private static String addressLiteral(InetAddress local) {
        String address = local.getHostAddress();
        int scope = address.indexOf(SCOPE_SEPARATOR);
        String unscoped = scope < 0 ? address : address.substring(0, scope);
        return local instanceof Inet6Address ? "[IPv6:" + unscoped + "]" : "[" + unscoped + "]";
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + CARRIAGE_RETURN + LINE_FEED).getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static final class ReplyReader {
        private final Socket socket;
        private final InputStream in;
        private final Duration stepTimeout;
        private long deadline;

        ReplyReader(Socket socket, Duration stepTimeout) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.stepTimeout = stepTimeout;
        }

        Reply next() throws IOException {
            deadline = System.nanoTime() + stepTimeout.toNanos();
            List<String> lines = new ArrayList<>();
            int code = -1;
            while (lines.size() < MAX_REPLY_LINES) {
                String line = readLine();
                int lineCode = codeOf(line);
                if (lineCode < 0 || (code >= 0 && lineCode != code)) {
                    throw new IOException("The mail server sent a malformed reply");
                }
                code = lineCode;
                lines.add(line.length() > CODE_DIGITS ? line.substring(CODE_DIGITS + 1) : "");
                if (line.length() == CODE_DIGITS || line.charAt(CODE_DIGITS) != CONTINUATION) {
                    return new Reply(code, lines);
                }
            }
            throw new IOException("The mail server sent more than " + MAX_REPLY_LINES + " reply lines");
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int next = read();
                if (next < 0) {
                    throw new IOException("The mail server closed the connection");
                }
                if (next == LINE_FEED) {
                    return line.toString(StandardCharsets.US_ASCII);
                }
                if (next != CARRIAGE_RETURN) {
                    line.write(next);
                }
                if (line.size() > MAX_LINE_BYTES) {
                    throw new IOException("The mail server sent a reply line longer than " + MAX_LINE_BYTES + " bytes");
                }
            }
        }

        private int read() throws IOException {
            long remainingMillis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
            if (remainingMillis <= 0) {
                throw new SocketTimeoutException("The mail server did not answer within " + stepTimeout.toSeconds() + " seconds");
            }
            if (in.available() == 0) {
                socket.setSoTimeout(Math.toIntExact(remainingMillis));
            }
            return in.read();
        }

        private static int codeOf(String line) {
            if (line.length() < CODE_DIGITS) {
                return -1;
            }
            for (int index = 0; index < CODE_DIGITS; index++) {
                if (!Character.isDigit(line.charAt(index))) {
                    return -1;
                }
            }
            if (line.length() > CODE_DIGITS && line.charAt(CODE_DIGITS) != CONTINUATION && line.charAt(CODE_DIGITS) != ' ') {
                return -1;
            }
            return Integer.parseInt(line.substring(0, CODE_DIGITS));
        }
    }

    private record Reply(int code, List<String> lines) {
        boolean offers(String extension) {
            return lines.stream().skip(1).map(line -> line.trim().split("\\s+", 2)[0].toUpperCase(Locale.ROOT))
                .anyMatch(extension::equals);
        }
    }
}
