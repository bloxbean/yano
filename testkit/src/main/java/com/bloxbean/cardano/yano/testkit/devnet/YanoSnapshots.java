package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Snapshot helpers backed by {@link DevnetControl}.
 */
public final class YanoSnapshots {
    private final DevnetControl devnet;

    YanoSnapshots(DevnetControl devnet) {
        this.devnet = Objects.requireNonNull(devnet, "devnet");
    }

    /**
     * Creates a devnet snapshot.
     *
     * @param name snapshot name
     * @return snapshot metadata
     */
    public SnapshotInfo create(String name) {
        requireName(name);
        return devnet.createDevnetSnapshot(name);
    }

    /**
     * Restores a devnet snapshot.
     *
     * @param name snapshot name
     */
    public void restore(String name) {
        requireName(name);
        devnet.restoreDevnetSnapshot(name);
    }

    /**
     * Restores a devnet snapshot and returns the restored tip.
     *
     * @param name snapshot name
     * @return restore result
     */
    public DevnetRestoreResult restoreAndGetTip(String name) {
        requireName(name);
        return devnet.restoreDevnetSnapshotAndGetTip(name);
    }

    /**
     * Lists devnet snapshots.
     *
     * @return snapshot metadata
     */
    public List<SnapshotInfo> list() {
        return devnet.listDevnetSnapshots();
    }

    /**
     * Deletes a devnet snapshot.
     *
     * @param name snapshot name
     */
    public void delete(String name) {
        requireName(name);
        devnet.deleteDevnetSnapshot(name);
    }

    /**
     * Checks if a snapshot exists.
     *
     * @param name snapshot name
     * @return true if present
     */
    public boolean exists(String name) {
        requireName(name);
        return list().stream().anyMatch(snapshot -> name.equals(snapshot.name()));
    }

    /**
     * Creates a snapshot, runs an action, and restores the snapshot afterward.
     * The snapshot remains available for explicit inspection or deletion.
     *
     * @param name snapshot name
     * @param action action to run
     */
    public void withSnapshot(String name, Runnable action) {
        Objects.requireNonNull(action, "action");
        try {
            withSnapshot(name, () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Snapshot action failed", e);
        }
    }

    /**
     * Creates a snapshot, runs an action, restores the snapshot afterward, and
     * returns the action result. The snapshot remains available for explicit
     * inspection or deletion.
     *
     * @param name snapshot name
     * @param action action to run
     * @param <T> result type
     * @return action result
     * @throws Exception if the action fails
     */
    public <T> T withSnapshot(String name, Callable<T> action) throws Exception {
        requireName(name);
        Objects.requireNonNull(action, "action");

        create(name);
        Throwable failure = null;
        try {
            return action.call();
        } catch (Exception | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                restore(name);
            } catch (RuntimeException | Error restoreFailure) {
                if (failure != null) {
                    failure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("snapshot name must not be blank");
        }
    }
}
