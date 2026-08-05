package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.nio.file.Path;

/** Parsed, bounded public demo settings. */
public record EutxoDemoOptions(
        String command,
        String scenario,
        Path workspace,
        String name,
        String chainId,
        int members,
        int count,
        int httpPortBase,
        int serverPortBase,
        String targetBase,
        Path operatorSeedFile,
        String address,
        String l2Address,
        String l2PublicKey,
        long amount,
        Path output,
        Path signedTransaction,
        Format format,
        boolean confirmed,
        boolean help
) {
    public enum Format {
        TEXT,
        JSON
    }
}
