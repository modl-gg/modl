package gg.modl.backend.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InClauseChunksTest {
    @Test
    void chunksHoldAtMostFiveHundredValues() {
        List<List<Integer>> chunks = InClauseChunks.of(range(1001));

        assertEquals(List.of(500, 500, 1), chunks.stream().map(List::size).toList());
        assertEquals(range(1001), chunks.stream().flatMap(List::stream).toList());
    }

    @Test
    void duplicatesAreSentOnceInFirstSeenOrder() {
        assertEquals(List.of(List.of(3, 1, 2)), InClauseChunks.of(List.of(3, 1, 3, 2, 1)));
    }

    @Test
    void anEmptyCollectionHasNoChunks() {
        assertTrue(InClauseChunks.of(List.of()).isEmpty());
    }

    @Test
    void collectRunsTheQueryOncePerChunkAndConcatenatesTheResults() {
        List<Integer> chunkSizes = new ArrayList<>();

        List<Integer> results = InClauseChunks.collect(range(1200), chunk -> {
            chunkSizes.add(chunk.size());
            return chunk;
        });

        assertEquals(range(1200), results);
        assertEquals(List.of(500, 500, 200), chunkSizes);
    }

    @Test
    void sumAddsTheCountOfEveryChunk() {
        assertEquals(1200, InClauseChunks.sum(range(1200), List::size));
    }

    private static List<Integer> range(int size) {
        return IntStream.range(0, size).boxed().toList();
    }
}
