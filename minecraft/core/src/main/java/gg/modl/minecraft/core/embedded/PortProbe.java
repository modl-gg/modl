package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.NetworkAddress;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

final class PortProbe {
    private static final long RETRY_MILLIS = 250;

    private PortProbe() {
    }

    static void requireAvailable(NetworkAddress bind, String configPath) {
        Outcome outcome = probe(bind);
        if (outcome != Outcome.AVAILABLE) throw new EmbeddedServerException(outcome.describe(bind, configPath));
    }

    static void awaitAvailable(NetworkAddress bind, String configPath, long waitMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (probe(bind) == Outcome.PORT_IN_USE && System.nanoTime() - deadline < 0) pause();
        requireAvailable(bind, configPath);
    }

    private static void pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for the panel port");
        }
    }

    static Outcome probe(NetworkAddress bind) {
        InetSocketAddress socketAddress;
        try {
            socketAddress = socketAddress(bind);
        } catch (UnknownHostException unresolvable) {
            return Outcome.UNRESOLVABLE_ADDRESS;
        }
        if (socketAddress == null) return Outcome.FOREIGN_ADDRESS;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(socketAddress);
            return Outcome.AVAILABLE;
        } catch (BindException e) {
            return classify(e);
        } catch (IOException e) {
            return Outcome.FOREIGN_ADDRESS;
        }
    }

    private static @Nullable InetSocketAddress socketAddress(NetworkAddress bind) throws UnknownHostException {
        if (bind.isWildcard()) return new InetSocketAddress(bind.getPort());
        InetAddress address = InetAddress.getByName(bind.getHost());
        if (!address.isLoopbackAddress() && !belongsToThisMachine(address)) return null;
        return new InetSocketAddress(address, bind.getPort());
    }

    private static boolean belongsToThisMachine(InetAddress address) {
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException interfacesUnreadable) {
            return true;
        }
    }

    private static Outcome classify(BindException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("permission") || message.contains("access")) return Outcome.PERMISSION_DENIED;
        if (message.contains("assign")) return Outcome.FOREIGN_ADDRESS;
        return Outcome.PORT_IN_USE;
    }

    enum Outcome {
        AVAILABLE {
            @Override
            String describe(NetworkAddress bind, String configPath) {
                return "Port " + bind.getPort() + " is available.";
            }
        },
        PORT_IN_USE {
            @Override
            String describe(NetworkAddress bind, String configPath) {
                return "Port " + bind.getPort() + " is already in use, so the built-in panel cannot start. "
                        + "Stop whatever uses it or change embedded.port in " + configPath + ".";
            }
        },
        PERMISSION_DENIED {
            @Override
            String describe(NetworkAddress bind, String configPath) {
                return "This server is not allowed to listen on port " + bind.getPort()
                        + " (ports below 1024 usually need extra privileges). Change embedded.port in " + configPath + ".";
            }
        },
        UNRESOLVABLE_ADDRESS {
            @Override
            String describe(NetworkAddress bind, String configPath) {
                return "embedded.bind-address '" + bind.getHost() + "' cannot be resolved. Set it to an address of this "
                        + "machine or 0.0.0.0 in " + configPath + ".";
            }
        },
        FOREIGN_ADDRESS {
            @Override
            String describe(NetworkAddress bind, String configPath) {
                return "The built-in panel cannot listen on " + bind.getHost() + " because it is not an address of this "
                        + "machine. Set embedded.bind-address to one of its addresses or 0.0.0.0 in " + configPath + ".";
            }
        };

        abstract String describe(NetworkAddress bind, String configPath);
    }
}
