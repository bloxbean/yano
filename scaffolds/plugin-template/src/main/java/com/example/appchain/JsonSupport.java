package com.example.appchain;

/** Small dependency-free encoder used by this scaffold; never concatenate caller text into JSON. */
final class JsonSupport {
    private JsonSupport() {
    }

    static String string(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (character >= 0x20 && character <= 0x7e) {
                        encoded.append(character);
                    } else {
                        encoded.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            encoded.append(Character.forDigit(
                                    (character >>> shift) & 0x0f, 16));
                        }
                    }
                }
            }
        }
        return encoded.append('"').toString();
    }
}
