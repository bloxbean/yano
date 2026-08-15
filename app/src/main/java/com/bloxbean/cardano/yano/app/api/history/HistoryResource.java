package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** Finalized cross-dataset archive consistency metadata. */
@Path("history")
@Produces(MediaType.APPLICATION_JSON)
public class HistoryResource {
    @Inject
    HistoryArchiveService history;

    @GET
    @Path("watermark")
    public Response watermark(@QueryParam("datasets") String selected,
                              @QueryParam("from-block") Long fromBlock,
                              @QueryParam("at-or-before-block") Long atOrBeforeBlock,
                              @QueryParam("at-or-before-slot") Long atOrBeforeSlot) {
        try {
            Set<ArchiveDatasetId> datasets = parseDatasets(selected);
            long start = fromBlock == null ? history.firstCanonicalHistoryBlock() : fromBlock;
            return Response.ok(history.finalizedWatermark(datasets, start,
                    optional(atOrBeforeBlock), optional(atOrBeforeSlot))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (ArchiveStoreException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    private Set<ArchiveDatasetId> parseDatasets(String selected) {
        if (selected == null || selected.isBlank()) return history.enabledBlockDatasets();
        EnumSet<ArchiveDatasetId> datasets = EnumSet.noneOf(ArchiveDatasetId.class);
        Arrays.stream(selected.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(HistoryResource::dataset)
                .forEach(datasets::add);
        if (datasets.isEmpty()) throw new IllegalArgumentException("at least one dataset is required");
        return Set.copyOf(datasets);
    }

    private static ArchiveDatasetId dataset(String name) {
        return Arrays.stream(ArchiveDatasetId.values())
                .filter(dataset -> dataset.logicalName().equalsIgnoreCase(name)
                        || dataset.name().equalsIgnoreCase(name.replace('-', '_')))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown archive dataset: " + name));
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
