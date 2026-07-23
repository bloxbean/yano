package com.bloxbean.cardano.yano.api.plugin.domain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.AppQueryPath;

import java.util.List;

/** Query-only view of the app chains hosted by this node. */
public interface DomainQueryService {
    int MAX_CHAIN_IDS = 256;
    int MAX_CHAIN_ID_LENGTH = 160;
    int MAX_PATH_LENGTH = AppQueryPath.MAX_LENGTH;
    int MAX_PATH_SEGMENTS = AppQueryPath.MAX_SEGMENTS;
    int MAX_REQUEST_BYTES = 64 * 1024;

    /** Returns a stable snapshot of hosted chain ids in deterministic order. */
    List<String> chainIds();

    /**
     * Queries the host-selected state machine for {@code chainId} against one
     * root-fixed committed view. The returned result names the machine and
     * committed root selected by the host.
     */
    AppQueryResult query(String chainId, String path, byte[] params);
}
