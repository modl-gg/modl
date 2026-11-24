package gg.modl.minecraft.core.imports;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportConfirmationTest {
    private static final String SOURCE = "litebans";

    private final AtomicLong now = new AtomicLong();
    private final ImportConfirmation confirmation = new ImportConfirmation(now::get);

    @Test
    void nothingCanBeConfirmedBeforeAPreview() {
        assertFalse(confirmation.isArmed(SOURCE));
        assertFalse(confirmation.consume(SOURCE));
    }

    @Test
    void aConfirmationWorksOnce() {
        confirmation.arm(SOURCE);

        assertTrue(confirmation.consume(SOURCE));
        assertFalse(confirmation.consume(SOURCE));
    }

    @Test
    void aConfirmationOnlyStartsTheSourceThatWasPreviewed() {
        confirmation.arm(SOURCE);

        assertFalse(confirmation.isArmed("advancedban"));
        assertFalse(confirmation.consume("advancedban"));
    }

    @Test
    void aConfirmationExpiresAfterAMinute() {
        confirmation.arm(SOURCE);
        now.addAndGet(ImportConfirmation.VALID_FOR.toNanos());
        assertTrue(confirmation.isArmed(SOURCE));

        now.incrementAndGet();

        assertFalse(confirmation.isArmed(SOURCE));
        assertFalse(confirmation.consume(SOURCE));
    }

    @Test
    void aNewPreviewWithdrawsTheOldConfirmation() {
        confirmation.arm(SOURCE);
        confirmation.disarm();

        assertFalse(confirmation.consume(SOURCE));
    }
}
