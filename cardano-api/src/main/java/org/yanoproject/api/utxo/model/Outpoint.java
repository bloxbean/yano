package org.yanoproject.api.utxo.model;

/**
 * Transaction outpoint (tx hash + output index).
 */
public record Outpoint(String txHash, int index) {}

