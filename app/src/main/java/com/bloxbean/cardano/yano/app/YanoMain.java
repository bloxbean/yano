package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.runtime.utxo.PointerIndexRepair;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Normal Quarkus entry point plus explicitly selected offline maintenance. */
@QuarkusMain
public final class YanoMain {
    static final String REPAIR_CONFIRMATION = "REPAIR_POINTER_INDEX";
    private static final int NOT_A_COMMAND = -1;

    private YanoMain() {
    }

    public static void main(String... arguments) {
        int result = runOfflineCommand(arguments, System.out, System.err);
        if (result != NOT_A_COMMAND) {
            System.exit(result);
        }
        Quarkus.run(arguments);
    }

    static int runOfflineCommand(
            String[] arguments, PrintStream output, PrintStream error) {
        if (arguments.length < 2
                || !"repair".equals(arguments[0])
                || !"pointer-index".equals(arguments[1])) {
            return NOT_A_COMMAND;
        }
        try {
            Map<String, String> options = parseOptions(arguments, 2);
            String database = options.get("--database");
            if (database == null || database.isBlank()) {
                throw new IllegalArgumentException("--database is required");
            }
            if (!REPAIR_CONFIRMATION.equals(options.get("--confirm"))) {
                throw new IllegalArgumentException(
                        "required: --confirm " + REPAIR_CONFIRMATION);
            }
            PointerIndexRepair.RepairResult result =
                    PointerIndexRepair.repair(Path.of(database));
            String action = result.repaired() ? "repaired" : "already ready";
            output.printf(
                    "Pointer UTXO index %s at block %d, slot %d, hash %s; "
                            + "verified %,d live UTXOs and %,d pointer rows in %s%n",
                    action,
                    result.coordinate().blockNumber(),
                    result.coordinate().slot(),
                    HexUtil.encodeHexString(result.coordinate().blockHash()),
                    result.unspentRows(),
                    result.pointerRows(),
                    result.elapsed());
            return 0;
        } catch (IllegalArgumentException invalid) {
            error.println("Pointer index repair rejected: " + invalid.getMessage());
            return 2;
        } catch (Exception failure) {
            error.println("Pointer index repair failed: " + failure.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parseOptions(
            String[] arguments, int offset) {
        if ((arguments.length - offset) % 2 != 0) {
            throw new IllegalArgumentException(
                    "arguments must be --name value pairs");
        }
        Map<String, String> result = new HashMap<>();
        for (int index = offset; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (!"--database".equals(name) && !"--confirm".equals(name)) {
                throw new IllegalArgumentException("unsupported option: " + name);
            }
            if (result.put(name, arguments[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate option: " + name);
            }
        }
        return result;
    }
}
