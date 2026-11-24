package com.alessiodp.libby.download;

import java.io.InterruptedIOException;

public final class RetryPolicy {
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 1000);

    private final int attemptsPerUrl;
    private final long initialBackoffMillis;

    private RetryPolicy(int attemptsPerUrl, long initialBackoffMillis) {
        if (attemptsPerUrl < 1) throw new IllegalArgumentException("attemptsPerUrl must be at least 1");
        if (initialBackoffMillis < 0) throw new IllegalArgumentException("initialBackoffMillis must not be negative");
        this.attemptsPerUrl = attemptsPerUrl;
        this.initialBackoffMillis = initialBackoffMillis;
    }

    public static RetryPolicy of(int attemptsPerUrl, long initialBackoffMillis) {
        return new RetryPolicy(attemptsPerUrl, initialBackoffMillis);
    }

    boolean allowsAnotherAttempt(int failedAttempts) {
        return failedAttempts < attemptsPerUrl;
    }

    void pauseAfter(int failedAttempts) throws InterruptedIOException {
        long backoff = initialBackoffMillis << Math.min(failedAttempts - 1, 10);
        if (backoff == 0) return;
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Download cancelled");
        }
    }
}
