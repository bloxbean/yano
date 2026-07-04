package com.bloxbean.cardano.yano.scalusbridge;

import java.util.Locale;

public final class ScalusNativeFailures {
    public static final String BLS_UNAVAILABLE_RULE = "Bls12_381BuiltinsUnavailable";
    public static final String BLS_UNAVAILABLE_MESSAGE =
            "BLS12-381 Plutus builtins are unavailable in this deployment: "
                    + "the Scalus blst JNI library could not be loaded or registered.";

    private ScalusNativeFailures() {
    }

    public static boolean isBlsUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (mentionsBlst(current.getClass().getName()) || mentionsBlst(current.getMessage())) {
                return true;
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (mentionsBlst(frame.getClassName())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean mentionsBlst(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("supranational.blst")
                || lower.contains("blstjni")
                || lower.contains("libblst");
    }
}
