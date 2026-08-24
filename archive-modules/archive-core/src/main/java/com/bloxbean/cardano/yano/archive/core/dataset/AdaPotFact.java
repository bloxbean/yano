package com.bloxbean.cardano.yano.archive.core.dataset;

public record AdaPotFact(long treasury, long reserves, long deposits, long fees,
                         long distributed, long undistributed, long rewardsPot,
                         long poolRewardsPot) { }
