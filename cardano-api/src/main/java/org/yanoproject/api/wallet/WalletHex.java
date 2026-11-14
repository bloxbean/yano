package org.yanoproject.api.wallet;

/** Fixed-width validation without compiling a regular expression on the apply path. */
final class WalletHex {
    private WalletHex() { }

    static boolean valid(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
}
