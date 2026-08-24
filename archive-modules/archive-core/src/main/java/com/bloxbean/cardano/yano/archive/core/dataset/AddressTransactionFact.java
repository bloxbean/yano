package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.List;

public record AddressTransactionFact(byte[] txHash, int txIndex, List<AddressSubject> subjects,
                                     int inputCount, int outputCount, int collateralInputCount,
                                     int collateralReturnCount) {
    public AddressTransactionFact { subjects = List.copyOf(subjects); }
}
