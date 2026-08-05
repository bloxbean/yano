package com.bloxbean.cardano.yano.appchain.showcase.client;

import java.util.Arrays;

/**
 * Entry point of the showcase client fat jar: the first argument selects the
 * chain-family demo, the rest are forwarded to it. Today only the
 * authenticated-map demo exists; sibling demos (orders, kv-registry, eutxo,
 * ...) can register here later without changing the java -jar command shape.
 */
public final class ShowcaseClientDemos {

    private ShowcaseClientDemos() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            usage();
            System.exit(2);
        }
        String[] forwarded = Arrays.copyOfRange(arguments, 1, arguments.length);
        switch (arguments[0]) {
            case "authmap" -> ShowcaseAuthMapClientDemo.main(forwarded);
            case "eutxo" -> ShowcaseEutxoClientDemo.main(forwarded);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.err.println("""
                usage: java -jar yano-showcase-client-all.jar <demo> [args]
                  authmap <base-url> <chain-id> <scenario> [args]
                          scenarios: basic-put | governed-put | reads | verified-entry | load
                  eutxo   <base-url> <chain-id> <scenario> [args]
                          scenarios: deposit | utxos | transfer | claim | receipt | settle""");
    }
}
