package org.yanoproject.api.wallet;

public final class WalletIndexUnavailableException extends IllegalStateException {
    private final WalletIndexCoverage coverage;

    public WalletIndexUnavailableException(WalletIndexCoverage coverage) {
        super(coverage.unavailableReason() != null ? coverage.unavailableReason() : "Wallet index incomplete");
        this.coverage = coverage;
    }

    public WalletIndexCoverage coverage() { return coverage; }
}
