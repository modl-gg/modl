package gg.modl.backend.tunnel.cloudflared;

import gg.modl.update.ArtifactFiles;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CloudflaredInstaller implements CloudflaredSupervisor.BinarySource {
    static final String PARTIAL_SUFFIX = ".partial";

    private static final Set<PosixFilePermission> OWNER_EXECUTABLE =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final TunnelDirectory directory;
    private final CloudflaredBuild build;
    private final BinaryDownload download;

    public CloudflaredInstaller(TunnelDirectory directory, CloudflaredBuild build, BinaryDownload download) {
        this.directory = directory;
        this.build = build;
        this.download = download;
    }

    @Override
    public Path verifiedBinary(BooleanSupplier cancelled) throws TunnelUnavailableException {
        Path binary = directory.binary(build);
        try {
            directory.retainOnly(build);
            if (!matches(binary)) {
                downloadTo(binary, cancelled);
            }
            makeExecutable(binary);
            return binary;
        } catch (IOException e) {
            throw new TunnelUnavailableException("cloudflared cannot be installed in " + directory.root() + " ("
                + e.getMessage() + ")", e);
        }
    }

    private boolean matches(Path binary) throws IOException {
        if (!Files.isRegularFile(binary) || Files.size(binary) != build.size()) {
            return false;
        }
        boolean matches = ArtifactFiles.sha256(binary).equals(build.sha256());
        if (!matches) {
            log.info("{} does not match cloudflared {}; replacing it", binary, build.version());
        }
        return matches;
    }

    private void downloadTo(Path binary, BooleanSupplier cancelled) throws IOException, TunnelUnavailableException {
        Path partial = Files.createTempFile(binary.getParent(), binary.getFileName() + ".", PARTIAL_SUFFIX);
        log.info("Downloading cloudflared {} from {}", build.version(), build.source());
        String sha256;
        try {
            sha256 = download.download(build.source(), partial, build.size(), written -> {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("The tunnel stopped");
                }
            });
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw new TunnelUnavailableException("cloudflared " + build.version() + " could not be downloaded from "
                + build.source() + " (" + e.getMessage() + "). Without access to GitHub, put " + build.file()
                + " from that address in " + directory.root(), e);
        } catch (RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (!sha256.equals(build.sha256())) {
            Files.deleteIfExists(partial);
            throw new TunnelUnavailableException("The download of " + build.source()
                + " does not match the SHA-256 modl pins for cloudflared " + build.version());
        }
        makeExecutable(partial);
        moveAtomically(partial, binary);
        log.info("Installed cloudflared {} at {}", build.version(), binary);
    }

    private void makeExecutable(Path file) throws IOException, TunnelUnavailableException {
        if (build.platform().isWindows()) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, OWNER_EXECUTABLE);
        } catch (UnsupportedOperationException notPosix) {
            log.debug("{} has no POSIX permissions", file);
        } catch (IOException notOwner) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                || !Files.isExecutable(file)) {
                throw new TunnelUnavailableException(file + " can be changed by other users or cannot be run, and modl cannot"
                    + " restrict it (" + notOwner.getMessage() + ")", notOwner);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
