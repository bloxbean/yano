package org.yanoproject.ledger.amaru.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.TrapException;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The minimal WASI preview-1 host the module needs (ADR-057 invariant 2, INTERFACE.md "Imports"). It
 * grants no capability: no file system, sockets, arguments or environment.
 *
 * <ul>
 *   <li>{@code clock_time_get}: wall clock (id 0) or monotonic clock (other ids), in nanoseconds.
 *       Amaru only times its rule spans with it; the value never affects a verdict.</li>
 *   <li>{@code random_get}: non-cryptographic bytes (hash-map seeding only), written in 4 KiB chunks;
 *       a buffer outside the guest's memory is {@code EINVAL}.</li>
 *   <li>{@code environ_*}, {@code args_*}: empty.</li>
 *   <li>{@code fd_write}: the bytes (panic messages on stderr) go to this class's SLF4J logger at debug
 *       level, truncated to 4 KiB per call; nothing is written anywhere else.</li>
 *   <li>{@code proc_exit}: a trap. The caller rejects the transaction and discards the instance.</li>
 *   <li>{@code sched_yield}: a no-op.</li>
 * </ul>
 */
public final class WasiHost {

    private static final Logger log = LoggerFactory.getLogger(WasiHost.class);
    private static final String MODULE = "wasi_snapshot_preview1";
    private static final int ERRNO_SUCCESS = 0;
    private static final int ERRNO_INVAL = 28;
    private static final int MAX_LOGGED_BYTES = 4096;
    private static final int RANDOM_CHUNK = 4096;

    private WasiHost() {
    }

    public static ImportValues imports() {
        return ImportValues.builder()
                .addFunction(function("clock_time_get", List.of(ValType.I32, ValType.I64, ValType.I32),
                        (instance, args) -> clockTimeGet(instance, (int) args[0], (int) args[2])))
                .addFunction(function("random_get", List.of(ValType.I32, ValType.I32),
                        (instance, args) -> randomGet(instance, (int) args[0], (int) args[1])))
                .addFunction(function("environ_get", List.of(ValType.I32, ValType.I32), (instance, args) -> ok()))
                .addFunction(function("environ_sizes_get", List.of(ValType.I32, ValType.I32),
                        (instance, args) -> zeroSizes(instance, (int) args[0], (int) args[1])))
                .addFunction(function("args_get", List.of(ValType.I32, ValType.I32), (instance, args) -> ok()))
                .addFunction(function("args_sizes_get", List.of(ValType.I32, ValType.I32),
                        (instance, args) -> zeroSizes(instance, (int) args[0], (int) args[1])))
                .addFunction(function("fd_write", List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                        (instance, args) -> fdWrite(instance, (int) args[0], (int) args[1], (int) args[2],
                                (int) args[3])))
                .addFunction(new HostFunction(MODULE, "proc_exit", FunctionType.of(List.of(ValType.I32), List.of()),
                        (instance, args) -> {
                            throw new TrapException("proc_exit(" + (int) args[0] + ")");
                        }))
                .addFunction(function("sched_yield", List.of(), (instance, args) -> ok()))
                .build();
    }

    private interface Handler {
        long[] apply(Instance instance, long[] args);
    }

    private static HostFunction function(String name, List<ValType> params, Handler handler) {
        return new HostFunction(MODULE, name, FunctionType.of(params, List.of(ValType.I32)), handler::apply);
    }

    private static long[] ok() {
        return new long[]{ERRNO_SUCCESS};
    }

    private static long[] clockTimeGet(Instance instance, int clockId, int timePtr) {
        long nanos = clockId == 0
                ? Math.multiplyExact(System.currentTimeMillis(), 1_000_000L)
                : System.nanoTime();
        instance.memory().writeLong(timePtr, nanos);
        return ok();
    }

    /** Fills the guest buffer in chunks, so a hostile length cannot make the host allocate it at once. */
    private static long[] randomGet(Instance instance, int buf, int len) {
        Memory memory = instance.memory();
        if (len < 0 || (long) buf + len > (long) memory.pages() * Memory.PAGE_SIZE) {
            return new long[]{ERRNO_INVAL};
        }
        byte[] chunk = new byte[Math.min(len, RANDOM_CHUNK)];
        for (int done = 0; done < len; done += chunk.length) {
            int size = Math.min(chunk.length, len - done);
            ThreadLocalRandom.current().nextBytes(chunk);
            memory.write(buf + done, chunk, 0, size);
        }
        return ok();
    }

    private static long[] zeroSizes(Instance instance, int countPtr, int sizePtr) {
        Memory memory = instance.memory();
        memory.writeI32(countPtr, 0);
        memory.writeI32(sizePtr, 0);
        return ok();
    }

    private static long[] fdWrite(Instance instance, int fd, int iovs, int iovsLen, int nwrittenPtr) {
        Memory memory = instance.memory();
        long written = 0;
        StringBuilder text = log.isDebugEnabled() ? new StringBuilder() : null;
        for (int i = 0; i < iovsLen; i++) {
            int base = memory.readInt(iovs + i * 8);
            int length = memory.readInt(iovs + i * 8 + 4);
            if (length < 0) {
                return new long[]{ERRNO_INVAL};
            }
            written += length;
            if (text != null && text.length() < MAX_LOGGED_BYTES) {
                int take = Math.min(length, MAX_LOGGED_BYTES - text.length());
                text.append(new String(memory.readBytes(base, take), StandardCharsets.UTF_8));
            }
        }
        if (text != null && !text.isEmpty()) {
            log.debug("amaru wasm fd {}: {}", fd, text.toString().stripTrailing());
        }
        memory.writeI32(nwrittenPtr, (int) Math.min(written, Integer.MAX_VALUE));
        return ok();
    }
}
