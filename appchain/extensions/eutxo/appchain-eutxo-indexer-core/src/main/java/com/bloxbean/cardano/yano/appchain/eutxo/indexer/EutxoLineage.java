package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.List;

public record EutxoLineage(
        List<Node> nodes,
        List<Edge> edges,
        boolean truncated
) {
    public EutxoLineage {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Node(String kind, String id, String status) {
    }

    public record Edge(String from, String to, String relation) {
    }
}
