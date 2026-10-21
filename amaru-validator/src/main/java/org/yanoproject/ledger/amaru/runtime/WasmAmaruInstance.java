package org.yanoproject.ledger.amaru.runtime;

import org.yanoproject.ledger.amaru.generated.AmaruValidatorModule;

import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryLimits;

import java.nio.charset.StandardCharsets;

/**
 * An instance of the build-time AOT-compiled module (Endive): the machine classes generated from
 * {@code amaru_validator.wasm} at build time and the stripped {@code .meta} module they load. The runtime
 * compiler is never used, so this also runs in a GraalVM native image.
 *
 * <p>Memory is a {@link ByteArrayMemory} whose page limit is the configured {@code max-memory-pages}; the
 * module's own declaration has no maximum. A grow past the limit fails inside the guest, which then
 * aborts (a trap).</p>
 */
public final class WasmAmaruInstance implements AmaruInstance {

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;
    private final ExportFunction requiredKeys;
    private final ExportFunction validate;

    /**
     * Instantiates the module and runs its reactor initialiser ({@code _initialize}).
     *
     * @param maxMemoryPages the linear-memory page limit
     * @throws IllegalArgumentException when the module needs more initial pages than the limit
     */
    public WasmAmaruInstance(int maxMemoryPages) {
        WasmModule module = AmaruValidatorModule.load();
        int initialPages = module.memorySection()
                .map(section -> section.getMemory(0).limits().initialPages())
                .orElseThrow(() -> new IllegalStateException("the module declares no memory"));
        if (initialPages > maxMemoryPages) {
            throw new IllegalArgumentException("max-memory-pages " + maxMemoryPages
                    + " is below the module's initial memory of " + initialPages + " pages");
        }
        this.instance = Instance.builder(module)
                .withMachineFactory(AmaruValidatorModule::create)
                .withImportValues(WasiHost.imports())
                .withMemoryFactory(ByteArrayMemory::new)
                .withMemoryLimits(new MemoryLimits(initialPages, maxMemoryPages))
                .withStart(false)
                .build();
        this.memory = instance.memory();
        this.alloc = instance.export("alloc");
        this.dealloc = instance.export("dealloc");
        this.requiredKeys = instance.export("required_keys");
        this.validate = instance.export("validate");
        instance.export("_initialize").apply();
    }

    @Override
    public int abiVersion() {
        return (int) instance.export("abi_version").apply()[0];
    }

    @Override
    public String amaruVersion() {
        return new String(readResponse((int) instance.export("amaru_version").apply()[0]), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] requiredKeys(byte[] transaction, byte[] env) {
        // Input buffers are freed only after a successful call: after a trap or an interrupt the instance
        // is discarded, and calling into it again would hide the original exception.
        int txPtr = write(transaction);
        int envPtr = write(env);
        byte[] response = readResponse((int) requiredKeys.apply(txPtr, transaction.length, envPtr, env.length)[0]);
        free(txPtr, transaction.length);
        free(envPtr, env.length);
        return response;
    }

    @Override
    public byte[] validate(byte[] request) {
        return validate(request, null);
    }

    /**
     * Test hook: {@link #validate(byte[])}, running {@code beforeCall} on the calling thread after the
     * request is in guest memory and just before the {@code validate} export is entered.
     */
    public byte[] validate(byte[] request, Runnable beforeCall) {
        int ptr = write(request);
        if (beforeCall != null) {
            beforeCall.run();
        }
        byte[] response = readResponse((int) validate.apply(ptr, request.length)[0]);
        free(ptr, request.length);
        return response;
    }

    /**
     * Test hook: calls {@code validate} with a pointer and length outside the instance's memory, which
     * traps in the guest (an out-of-bounds read).
     */
    public void callValidateOutOfBounds() {
        validate.apply(Integer.MAX_VALUE - 16, 1024);
    }

    private int write(byte[] data) {
        int ptr = (int) alloc.apply(data.length)[0];
        if (ptr == 0 && data.length > 0) {
            throw new IllegalStateException("alloc(" + data.length + ") returned null");
        }
        memory.write(ptr, data, 0, data.length);
        return ptr;
    }

    private void free(int ptr, int length) {
        if (ptr != 0 || length == 0) {
            dealloc.apply(ptr, length);
        }
    }

    /** {@code [u32 LE length][payload]}; the host copies the payload, then frees the buffer. */
    private byte[] readResponse(int ptr) {
        if (ptr == 0) {
            throw new IllegalStateException("the module returned a null response buffer");
        }
        int length = memory.readInt(ptr);
        if (length < 0) {
            throw new IllegalStateException("response length out of range: " + Integer.toUnsignedString(length));
        }
        byte[] payload = memory.readBytes(ptr + 4, length);
        dealloc.apply(ptr, 4L + length);
        return payload;
    }

    @Override
    public void close() {
        instance.close();
    }
}
