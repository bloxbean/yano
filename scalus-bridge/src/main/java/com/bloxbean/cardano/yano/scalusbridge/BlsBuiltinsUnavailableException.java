package com.bloxbean.cardano.yano.scalusbridge;

final class BlsBuiltinsUnavailableException extends Exception {
    BlsBuiltinsUnavailableException(Throwable cause) {
        super(ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE, cause);
    }
}
