package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.app.api.ApiGroup;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.app.archive.HistoryArchiveService;
import com.bloxbean.cardano.yano.app.archive.ProjectionHistoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Finalized cross-dataset archive consistency metadata. */
@Extension(name = ApiGroup.CORE, value = "")
@Path("history")
@Produces(MediaType.APPLICATION_JSON)
public class HistoryResource {
    @Inject
    HistoryArchiveService history;

    @Inject
    ProjectionHistoryService projection;

    /**
     * What the archive can answer for right now.
     *
     * <p>Separate from {@code watermark}, which reports cross-dataset projection consistency.
     * This reports the projection archive's committed range, its identity, and the epoch artifact
     * contracts it is maintained under - the last of which cannot be derived from the section
     * fingerprint.
     *
     * <p>Callers must treat blocks above {@code queryableThroughBlock} as unknown, not absent.
     * Near tip a block can be final and durable and still be up to one batch linger plus one
     * maintenance budget away from being queryable.
     */
    @GET
    @Path("coverage")
    public Response coverage(@QueryParam("dataset") String dataset,
                             @QueryParam("from-epoch") Integer fromEpoch,
                             @QueryParam("to-epoch") Integer toEpoch,
                             @QueryParam("offset") Integer offset,
                             @QueryParam("limit") Integer limit) {
        try {
            if (dataset == null || dataset.isBlank()) {
                return Response.ok(projection.coverage()).build();
            }
            return Response.ok(projection.coverageDetails(HistoryResource.dataset(dataset),
                    fromEpoch, toEpoch, offset, limit)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Resume future capture for a paused epoch artifact without hiding its retained gaps. */
    @POST
    @Path("coverage/{dataset}/resume")
    public Response resume(@PathParam("dataset") String dataset) {
        try {
            return Response.ok(projection.resumeEpochArtifact(dataset(dataset))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException | ArchiveStoreException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("coverage/legacy-staging-failure/acknowledge")
    public Response acknowledgeLegacyStagingFailure() {
        try {
            return Response.ok(projection.acknowledgeLegacyStagingFailure()).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("watermark")
    public Response watermark(@QueryParam("datasets") String selected,
                              @QueryParam("from-block") Long fromBlock,
                              @QueryParam("at-or-before-block") Long atOrBeforeBlock,
                              @QueryParam("at-or-before-slot") Long atOrBeforeSlot) {
        try {
            Set<ArchiveDatasetId> datasets = parseDatasets(selected);
            var projected = projection.consistencyPoint(datasets);
            if (projected.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "projection history is unavailable"))
                        .build();
            }
            return Response.ok(projected.get()).build();
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

}
