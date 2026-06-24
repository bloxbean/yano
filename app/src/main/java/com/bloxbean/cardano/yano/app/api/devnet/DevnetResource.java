package com.bloxbean.cardano.yano.app.api.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.app.api.devnet.dto.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Path("devnet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DevnetResource {

    private static final Logger log = LoggerFactory.getLogger(DevnetResource.class);

    @Inject
    NodeLifecycle nodeLifecycle;

    @Inject
    DevnetControl devnetControl;

    private void requireDevMode() {
        if (!(nodeLifecycle.getConfig() instanceof YanoConfig config) || !config.isDevMode()) {
            throw new DevnetOnlyException("This endpoint requires dev mode (set yano.dev-mode=true)");
        }
    }

    // --- Rollback ---

    @POST
    @Path("/rollback")
    public Response rollback(RollbackRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        DevnetRollbackResult result;
        try {
            DevnetRollbackTarget target = request != null
                    ? new DevnetRollbackTarget(request.slot(), request.blockNumber(), request.count())
                    : new DevnetRollbackTarget(null, null, null);
            result = devnetControl.rollbackDevnet(target);
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Rollback failed: " + e.getMessage()))
                    .build();
        }

        return Response.ok(new RollbackResponse(
                "Rolled back to slot " + result.slot() + ", block " + result.blockNumber(),
                result.slot(), result.blockNumber()
        )).build();
    }

    // --- Snapshot ---

    @POST
    @Path("/snapshot")
    public Response createDevnetSnapshot(SnapshotRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            SnapshotInfo info = devnetControl.createDevnetSnapshot(request.name());
            return Response.ok(new SnapshotResponse(
                    info.name(), info.slot(), info.blockNumber(), info.createdAt()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Snapshot failed: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/restore/{name}")
    public Response restoreDevnetSnapshot(@PathParam("name") String name) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        DevnetRestoreResult result;
        try {
            result = devnetControl.restoreDevnetSnapshotAndGetTip(name);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Restore failed: " + e.getMessage()))
                    .build();
        }

        return Response.ok(Map.of(
                "message", "Restored snapshot '" + name + "'",
                "slot", result.slot(),
                "block_number", result.blockNumber()
        )).build();
    }

    @GET
    @Path("/snapshots")
    public Response listDevnetSnapshots() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            List<SnapshotInfo> snapshots = devnetControl.listDevnetSnapshots();
            var response = snapshots.stream()
                    .map(s -> new SnapshotResponse(s.name(), s.slot(), s.blockNumber(), s.createdAt()))
                    .toList();
            return Response.ok(response).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/snapshot/{name}")
    public Response deleteDevnetSnapshot(@PathParam("name") String name) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            devnetControl.deleteDevnetSnapshot(name);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Delete failed: " + e.getMessage()))
                    .build();
        }

        return Response.ok(Map.of("message", "Snapshot '" + name + "' deleted")).build();
    }

    // --- Faucet ---

    @POST
    @Path("/fund")
    public Response fundAddress(FundRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        if (request.ada() == null || request.ada().signum() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ADA amount must be positive"))
                    .build();
        }
        long lovelace = request.ada().multiply(java.math.BigDecimal.valueOf(1_000_000)).longValueExact();

        try {
            FundResult result = devnetControl.fundAddress(request.address(), lovelace);
            return Response.ok(new FundResponse(
                    result.txHash(), result.index(), result.lovelace()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Fund failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Time/Slot Advance ---

    @POST
    @Path("/time/advance")
    public Response advanceTime(TimeAdvanceRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        // Validate exactly one of slots/seconds/epochs
        int paramCount = 0;
        if (request.slots() != null) paramCount++;
        if (request.seconds() != null) paramCount++;
        if (request.epochs() != null) paramCount++;
        if (paramCount != 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Exactly one of 'slots', 'seconds', or 'epochs' must be provided"))
                    .build();
        }

        try {
            TimeAdvanceResult result;
            if (request.slots() != null) {
                result = devnetControl.advanceTimeBySlots(request.slots());
            } else if (request.epochs() != null) {
                // Convert epochs to slots using configured epoch length
                YanoConfig config = (YanoConfig) nodeLifecycle.getConfig();
                long epochLength = config.getEpochLength();
                int slots = (int) (request.epochs() * epochLength);
                result = devnetControl.advanceTimeBySlots(slots);
            } else {
                result = devnetControl.advanceTimeBySeconds(request.seconds());
            }

            String message = "Advanced " + result.blocksProduced() + " blocks";
            return Response.ok(new TimeAdvanceResponse(
                    message, result.newSlot(), result.newBlockNumber(), result.blocksProduced()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Time advance failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Epoch Shift (Past Time Travel Mode) ---

    @POST
    @Path("/epochs/shift")
    public Response shiftEpochs(EpochShiftRequest request) {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            long shiftMillis = devnetControl.shiftGenesisAndStartProducer(request.epochs());

            YanoConfig config = (YanoConfig) nodeLifecycle.getConfig();
            String systemStart = Instant.ofEpochMilli(config.getGenesisTimestamp()).toString();

            return Response.ok(Map.of(
                    "message", "Shifted genesis back by " + request.epochs() + " epochs and started block producer",
                    "shift_millis", shiftMillis,
                    "new_system_start", systemStart,
                    "genesis_slot", 0
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Epoch shift failed: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/epochs/catch-up")
    public Response catchUpToWallClock() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        try {
            TimeAdvanceResult result = devnetControl.catchUpToWallClock();

            String message = "Caught up to wall-clock: " + result.blocksProduced() + " blocks produced";
            return Response.ok(new TimeAdvanceResponse(
                    message, result.newSlot(), result.newBlockNumber(), result.blocksProduced()
            )).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "Catch-up failed: " + e.getMessage()))
                    .build();
        }
    }

    // --- Genesis Download ---

    @GET
    @Path("/genesis/download")
    @Produces("application/zip")
    public Response downloadGenesis() {
        try {
            requireDevMode();
        } catch (DevnetOnlyException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        // Safe cast — requireDevMode() already verified config is YanoConfig
        YanoConfig config = (YanoConfig) nodeLifecycle.getConfig();

        // Collect genesis files
        Map<String, File> genesisFiles = new LinkedHashMap<>();
        addIfExists(genesisFiles, "shelley-genesis.json", config.getShelleyGenesisFile());
        addIfExists(genesisFiles, "byron-genesis.json", config.getByronGenesisFile());
        addIfExists(genesisFiles, "alonzo-genesis.json", config.getAlonzoGenesisFile());
        addIfExists(genesisFiles, "conway-genesis.json", config.getConwayGenesisFile());
        addIfExists(genesisFiles, "protocol-params.json", config.getProtocolParametersFile());

        if (genesisFiles.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "No genesis files available"))
                    .build();
        }

        StreamingOutput stream = output -> {
            try (ZipOutputStream zos = new ZipOutputStream(output)) {
                for (var entry : genesisFiles.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    try (FileInputStream fis = new FileInputStream(entry.getValue())) {
                        fis.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"genesis.zip\"")
                .build();
    }

    private void addIfExists(Map<String, File> map, String zipName, String path) {
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                map.put(zipName, f);
            }
        }
    }

    // --- Helpers ---

    /**
     * Exception for endpoints that are only available in devnet (block producer) mode.
     */
    public static class DevnetOnlyException extends RuntimeException {
        public DevnetOnlyException(String message) {
            super(message);
        }
    }
}
