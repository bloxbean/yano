package com.bloxbean.cardano.yano.archive.ducklake;

/**
 * Per-commit stage attribution for one DuckLake write session.
 *
 * <p>ADR-038 Phase 0 measured an isolated write session at roughly 35k rows/s
 * against roughly 420-780 rows/s observed on mainnet. Attributing that gap needs
 * the commit broken into its real stages rather than inferred from an append
 * microbenchmark, so the session records each one. The cost is a handful of
 * {@code System.nanoTime()} calls per commit, which is negligible beside the work
 * being measured.
 */
final class DuckLakeWriteStageTimings {
    private long appendNanos;
    private long verifyNanos;
    private long copyNanos;
    private long metadataNanos;
    private long commitNanos;
    private long locatorNanos;
    private long rows;

    void addAppend(long nanos) { appendNanos += nanos; }

    void addVerify(long nanos) { verifyNanos += nanos; }

    void addCopy(long nanos) { copyNanos += nanos; }

    void addMetadata(long nanos) { metadataNanos += nanos; }

    void addCommit(long nanos) { commitNanos += nanos; }

    void addLocator(long nanos) { locatorNanos += nanos; }

    void rows(long value) { rows = value; }

    long appendNanos() { return appendNanos; }

    long verifyNanos() { return verifyNanos; }

    long copyNanos() { return copyNanos; }

    long metadataNanos() { return metadataNanos; }

    long commitNanos() { return commitNanos; }

    long locatorNanos() { return locatorNanos; }

    long totalNanos() {
        return appendNanos + verifyNanos + copyNanos + metadataNanos + commitNanos + locatorNanos;
    }

    long rows() { return rows; }

    /** Single-line summary; percentages are of the measured stage total. */
    String summary() {
        long total = totalNanos();
        double scale = total == 0 ? 0 : 100.0 / total;
        return String.format(
                "rows=%d total=%.3fs append=%.3fs(%.1f%%) verifyKeys=%.3fs(%.1f%%) stagingCopy=%.3fs(%.1f%%) "
                        + "metadata=%.3fs(%.1f%%) commit=%.3fs(%.1f%%) locator=%.3fs(%.1f%%) rate=%.0f rows/s",
                rows, total / 1e9,
                appendNanos / 1e9, appendNanos * scale,
                verifyNanos / 1e9, verifyNanos * scale,
                copyNanos / 1e9, copyNanos * scale,
                metadataNanos / 1e9, metadataNanos * scale,
                commitNanos / 1e9, commitNanos * scale,
                locatorNanos / 1e9, locatorNanos * scale,
                total == 0 ? 0 : rows / (total / 1e9));
    }
}
