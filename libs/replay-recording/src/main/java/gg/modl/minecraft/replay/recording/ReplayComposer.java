package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import gg.modl.minecraft.replay.format.ReplayFileFormat;
import gg.modl.proto.modl.v1.ReplayEnd;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

final class ReplayComposer {
    static final long MIN_WINDOW_MS = 5_000L;
    static final int MIN_RADIUS_BLOCKS = 16;

    private final RecorderSettings settings;
    private final ServerVersion serverVersion;
    private final NameTable names;
    private final BlockStateNames blockNames;
    private final StoredPacketDecoder decoder;
    private final int fallingBlockType;
    private final Logger logger;

    ReplayComposer(RecorderSettings settings, ServerVersion serverVersion, NameTable names, int fallingBlockType, Logger logger) {
        this.settings = settings;
        this.serverVersion = serverVersion;
        this.names = names;
        this.blockNames = new BlockStateNames(serverVersion.toClientVersion());
        this.decoder = new StoredPacketDecoder(serverVersion);
        this.fallingBlockType = fallingBlockType;
        this.logger = logger;
    }

    ComposeResult composeWithinLimits(ComposeRequest request, ComposeTarget target) throws IOException {
        return composeWithin(request, target, ReplayBounds.UPLOAD);
    }

    ComposeResult composeWithin(ComposeRequest request, ComposeTarget target, ReplayBounds bounds) throws IOException {
        ComposeRequest attempt = request;
        while (true) {
            try (OutputStream out = target.open()) {
                return compose(attempt, out, bounds);
            } catch (ReplayTooLargeException tooLarge) {
                target.discard();
                ComposeRequest smaller = smaller(attempt, tooLarge.overflow());
                if (smaller == null) throw tooLarge;
                logger.fine("[replay] " + tooLarge.getMessage() + "; keeping " + smaller.getWindowMs() / 1000 + " seconds within "
                        + smaller.getRadiusBlocks() + " blocks");
                attempt = smaller;
            }
        }
    }

    static @Nullable ComposeRequest smaller(ComposeRequest attempt, ReplayTooLargeException.Overflow overflow) {
        long window = attempt.getWindowMs();
        int radius = attempt.getRadiusBlocks();
        long shorterWindow = overflow.shortensWindow() && window / 2 >= MIN_WINDOW_MS ? window / 2 : window;
        int narrowerRadius = overflow.narrowsRadius() && radius / 2 >= MIN_RADIUS_BLOCKS ? radius / 2 : radius;
        if (shorterWindow == window && narrowerRadius == radius) return null;
        return attempt.withWindowMs(shorterWindow).withRadiusBlocks(narrowerRadius);
    }

    ComposeResult compose(ComposeRequest request, OutputStream target) throws IOException {
        return compose(request, target, ReplayBounds.UPLOAD);
    }

    private ComposeResult compose(ComposeRequest request, OutputStream target, ReplayBounds bounds) throws IOException {
        try {
            return composeUnchecked(request, target, bounds);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private ComposeResult composeUnchecked(ComposeRequest request, OutputStream target, ReplayBounds bounds) throws IOException {
        RecordingSnapshot snapshot = request.getSnapshot();
        long windowStart = Math.max(0L, snapshot.getCaptureTime() - request.getWindowMs());

        PathTracker path = new PathTracker(snapshot.getBaseline().copy(), windowStart);
        for (JournalBlock block : snapshot.getBlocks()) block.replay(path);

        Map<Integer, WorldSegment> segments = new HashMap<>();
        for (WorldSegment segment : snapshot.getSegments()) segments.put(segment.getId(), segment);
        BiomeNames biomes = new BiomeNames(serverVersion.toClientVersion(), snapshot.getRegistries());
        SectionWriter sections = new SectionWriter(snapshot.getChunks(), segments, path, decoder, biomes, request.getRadiusBlocks(), logger);

        FileTables tables = new FileTables(names, blockNames);
        ModelProtos protos = new ModelProtos(tables, new LegacyStates(serverVersion, fallingBlockType));
        try (BoundedReplayWriter writer = new BoundedReplayWriter(target, bounds)) {
            WorldModel model = snapshot.getBaseline().copy();
            EventEmitter emitter = new EventEmitter(model, writer, tables, protos, sections, segments, windowStart,
                    snapshot.getStartEpochMs(), snapshot.getCaptureTime(), header -> header(header, request, snapshot, model, segments));
            for (JournalBlock block : snapshot.getBlocks()) block.replay(emitter);
            emitter.finish(snapshot.getCaptureTime());
            writer.write(ReplayFrame.newBuilder().setEnd(ReplayEnd.newBuilder()
                    .setDurationMs(emitter.durationMs(snapshot.getCaptureTime()))
                    .setEventCount((int) emitter.eventCount())
                    .setSectionCount((int) sections.sectionCount())
                    .setKeyframeCount((int) emitter.keyframeCount())
                    .setTruncated(snapshot.isTruncated())).build());
            return new ComposeResult(emitter.durationMs(snapshot.getCaptureTime()), emitter.eventCount(), sections.sectionCount());
        }
    }

    private ReplayHeader header(ReplayHeader.Builder header, ComposeRequest request, RecordingSnapshot snapshot, WorldModel model,
                                Map<Integer, WorldSegment> segments) {
        return header.setFormatVersion(ReplayFileFormat.FORMAT_VERSION)
                .setReplayId(request.getReplayId())
                .setMinecraftVersion(settings.getMinecraftVersion())
                .setProtocolVersion(serverVersion.getProtocolVersion())
                .setDataVersion(settings.getDataVersion())
                .setServerSoftware(settings.getServerSoftware())
                .setRecorderVersion(settings.getRecorderVersion())
                .setTargetUuid(snapshot.getTargetUuid().toString())
                .setTargetName(snapshot.getTargetName())
                .setTargetEntityId(model.getTargetEntityId())
                .setDimension(ModelProtos.dimension(segments.get(model.getSegment())))
                .setRadiusBlocks(request.getRadiusBlocks())
                .setHardcore(snapshot.isHardcore())
                .setLightRecorded(true)
                .setCaptureReason(request.getCaptureReason())
                .build();
    }
}
