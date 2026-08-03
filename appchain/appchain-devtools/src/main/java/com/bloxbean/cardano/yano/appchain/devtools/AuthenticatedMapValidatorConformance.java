package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;

import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Determinism and totality gate for trusted authenticated-map validator plugins. */
public final class AuthenticatedMapValidatorConformance {
    public static final int MAX_CASES = 4_096;
    public static final int MAX_TOTAL_INPUT_BYTES = 16 * 1_048_576;
    public static final int MAX_VIOLATIONS = 256;
    public static final int REPEAT_RUNS = 3;
    private static final long CONCURRENT_TIMEOUT_SECONDS = 15;

    private AuthenticatedMapValidatorConformance() {
    }

    /** Exercises fresh, repeated, reversed, and concurrent invocations. */
    public static Report verify(
            AuthenticatedMapValueValidatorFactory factory,
            ValidatorInitContext context,
            List<ValidationCase> corpus
    ) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(context, "context");
        List<ValidationCase> cases = requireCorpus(corpus);
        Evidence evidence = new Evidence();
        long allocatedBefore = allocatedBytes();
        long started = System.nanoTime();
        List<ValidatorVerdict> baseline = executeFresh(
                factory, context, cases, "fresh-forward", evidence);
        for (int run = 1; run < REPEAT_RUNS; run++) {
            List<ValidatorVerdict> repeated = executeFresh(
                    factory, context, cases, "repeat-" + run, evidence);
            compareVectors(baseline, repeated, "repeat-" + run, evidence);
        }

        List<ValidationCase> reverse = new ArrayList<>(cases);
        Collections.reverse(reverse);
        List<ValidatorVerdict> reversed = executeFresh(
                factory, context, reverse, "reverse", evidence);
        Collections.reverse(reversed);
        compareVectors(baseline, reversed, "reverse", evidence);
        concurrent(factory, context, cases, baseline, evidence);

        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes();
        long allocation = allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
                ? allocatedAfter - allocatedBefore : -1;
        return evidence.report(baseline, elapsed, allocation, cases);
    }

    /**
     * Repeats the gate across two child-first provider class loaders whose
     * optional classpaths may differ. Core SPI types remain shared by the
     * parent loader so the providers can be cast safely.
     */
    public static Report verifyIsolated(
            String factoryClassName,
            String childFirstPackage,
            List<URL> providerClasspath,
            List<URL> firstOptionalClasspath,
            List<URL> secondOptionalClasspath,
            ValidatorInitContext context,
            List<ValidationCase> corpus
    ) {
        requireClassName(factoryClassName, "factoryClassName");
        requireClassName(childFirstPackage, "childFirstPackage");
        List<URL> common = List.copyOf(Objects.requireNonNull(
                providerClasspath, "providerClasspath"));
        List<URL> firstExtras = List.copyOf(Objects.requireNonNull(
                firstOptionalClasspath, "firstOptionalClasspath"));
        List<URL> secondExtras = List.copyOf(Objects.requireNonNull(
                secondOptionalClasspath, "secondOptionalClasspath"));
        List<ValidationCase> cases = requireCorpus(corpus);

        try (ChildFirstLoader first = loader(common, firstExtras, childFirstPackage);
             ChildFirstLoader second = loader(common, secondExtras, childFirstPackage)) {
            Report firstReport = verify(instantiate(first, factoryClassName), context, cases);
            Report secondReport = verify(instantiate(second, factoryClassName), context, cases);
            List<String> violations = new ArrayList<>();
            addViolations(violations, "loader-1: ", firstReport.violations());
            addViolations(violations, "loader-2: ", secondReport.violations());
            if (!firstReport.verdictVectorSha256().equals(
                    secondReport.verdictVectorSha256())) {
                addViolation(violations,
                        "isolated class loaders produced different verdict vectors");
            }
            return new Report(violations.isEmpty(), List.copyOf(violations),
                    firstReport.verdictVectorSha256(),
                    firstReport.invocations() + secondReport.invocations(),
                    firstReport.elapsedNanos() + secondReport.elapsedNanos(),
                    addAllocations(firstReport.allocatedBytes(), secondReport.allocatedBytes()),
                    Math.max(firstReport.maximumInputBytes(),
                            secondReport.maximumInputBytes()));
        } catch (ReflectiveOperationException failure) {
            return failed("isolated provider construction failed: "
                    + failure.getClass().getSimpleName());
        } catch (java.io.IOException failure) {
            return failed("isolated class-loader cleanup failed");
        }
    }

    private static List<ValidatorVerdict> executeFresh(
            AuthenticatedMapValueValidatorFactory factory,
            ValidatorInitContext context,
            List<ValidationCase> cases,
            String run,
            Evidence evidence
    ) {
        AuthenticatedMapValueValidator validator;
        try {
            validator = Objects.requireNonNull(factory.create(copy(context)),
                    "factory returned null");
        } catch (Throwable failure) {
            rethrowFatal(failure);
            evidence.violation(run + " factory failure: "
                    + failure.getClass().getSimpleName());
            return Collections.nCopies(cases.size(), null);
        }
        List<ValidatorVerdict> result = new ArrayList<>(cases.size());
        for (ValidationCase testCase : cases) {
            result.add(invoke(validator, testCase, run, evidence));
        }
        return result;
    }

    private static ValidatorVerdict invoke(
            AuthenticatedMapValueValidator validator,
            ValidationCase testCase,
            String run,
            Evidence evidence
    ) {
        evidence.invocation();
        try {
            ValidatorVerdict verdict = validator.validate(
                    testCase.collectionId(), testCase.applicationKey(), testCase.value());
            if (verdict == null) {
                evidence.violation(run + "/" + testCase.name() + " returned null");
            } else if (verdict != testCase.expected()) {
                evidence.violation(run + "/" + testCase.name()
                        + " expected " + testCase.expected() + " but got " + verdict);
            }
            return verdict;
        } catch (Throwable failure) {
            rethrowFatal(failure);
            evidence.violation(run + "/" + testCase.name() + " threw "
                    + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static void concurrent(
            AuthenticatedMapValueValidatorFactory factory,
            ValidatorInitContext context,
            List<ValidationCase> cases,
            List<ValidatorVerdict> baseline,
            Evidence evidence
    ) {
        AuthenticatedMapValueValidator validator;
        try {
            validator = Objects.requireNonNull(factory.create(copy(context)),
                    "factory returned null");
        } catch (Throwable failure) {
            rethrowFatal(failure);
            evidence.violation("concurrent factory failure: "
                    + failure.getClass().getSimpleName());
            return;
        }
        int workers = Math.min(4, cases.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<IndexedVerdict>> futures = new ArrayList<>(cases.size());
            for (int index = cases.size() - 1; index >= 0; index--) {
                int captured = index;
                futures.add(executor.submit(() -> new IndexedVerdict(captured,
                        invoke(validator, cases.get(captured), "concurrent", evidence))));
            }
            for (Future<IndexedVerdict> future : futures) {
                try {
                    IndexedVerdict verdict = future.get(
                            CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!Objects.equals(baseline.get(verdict.index()), verdict.verdict())) {
                        evidence.violation("concurrent/" + cases.get(verdict.index()).name()
                                + " differs from the baseline verdict");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    evidence.violation("concurrent verification was interrupted");
                    return;
                } catch (ExecutionException failure) {
                    rethrowFatal(failure.getCause());
                    evidence.violation("concurrent verification escaped an exception");
                } catch (TimeoutException failure) {
                    evidence.violation("concurrent verification exceeded the conformance timeout");
                }
            }
        }
    }

    private static void compareVectors(
            List<ValidatorVerdict> expected,
            List<ValidatorVerdict> actual,
            String run,
            Evidence evidence
    ) {
        if (!expected.equals(actual)) {
            evidence.violation(run + " produced a different verdict vector");
        }
    }

    private static List<ValidationCase> requireCorpus(List<ValidationCase> corpus) {
        List<ValidationCase> result = List.copyOf(Objects.requireNonNull(corpus, "corpus"));
        if (result.isEmpty() || result.size() > MAX_CASES) {
            throw new IllegalArgumentException("corpus must contain 1-" + MAX_CASES + " cases");
        }
        int bytes = 0;
        for (ValidationCase testCase : result) {
            bytes = Math.addExact(bytes, testCase.applicationKey().length);
            bytes = Math.addExact(bytes, testCase.value().length);
            if (bytes > MAX_TOTAL_INPUT_BYTES) {
                throw new IllegalArgumentException(
                        "corpus input exceeds " + MAX_TOTAL_INPUT_BYTES + " bytes");
            }
        }
        return result;
    }

    private static ValidatorInitContext copy(ValidatorInitContext context) {
        return new ValidatorInitContext(context.descriptorId(), context.providerId(),
                context.contractVersion(), context.parameters(), context.collectionIds());
    }

    private static AuthenticatedMapValueValidatorFactory instantiate(
            ClassLoader loader,
            String factoryClassName
    ) throws ReflectiveOperationException {
        Object value = Class.forName(factoryClassName, true, loader)
                .getDeclaredConstructor().newInstance();
        return AuthenticatedMapValueValidatorFactory.class.cast(value);
    }

    private static ChildFirstLoader loader(
            List<URL> common,
            List<URL> extras,
            String childFirstPackage
    ) {
        List<URL> urls = new ArrayList<>(extras.size() + common.size());
        urls.addAll(extras);
        urls.addAll(common);
        return new ChildFirstLoader(urls.toArray(URL[]::new),
                AuthenticatedMapValidatorConformance.class.getClassLoader(),
                childFirstPackage);
    }

    private static void requireClassName(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > 512) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                || !allocation.isThreadAllocatedMemorySupported()) {
            return -1;
        }
        try {
            if (!allocation.isThreadAllocatedMemoryEnabled()) {
                allocation.setThreadAllocatedMemoryEnabled(true);
            }
            return allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private static long addAllocations(long first, long second) {
        return first < 0 || second < 0 ? -1 : Math.addExact(first, second);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
    }

    private static Report failed(String violation) {
        return new Report(false, List.of(violation), vectorDigest(List.of()),
                0, 0, -1, 0);
    }

    private static void addViolations(
            List<String> destination,
            String prefix,
            List<String> source
    ) {
        for (String violation : source) {
            addViolation(destination, prefix + violation);
        }
    }

    private static void addViolation(List<String> destination, String violation) {
        if (destination.size() < MAX_VIOLATIONS) {
            destination.add(violation);
        }
    }

    private static String vectorDigest(List<ValidatorVerdict> verdicts) {
        ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES + verdicts.size());
        bytes.putInt(verdicts.size());
        verdicts.forEach(verdict -> bytes.put(verdict == null
                ? (byte) 0xff : (byte) verdict.ordinal()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ValidationCase(
            String name,
            String collectionId,
            byte[] applicationKey,
            byte[] value,
            ValidatorVerdict expected
    ) {
        public ValidationCase {
            if (name == null || name.isBlank() || name.length() > 128
                    || collectionId == null || collectionId.isBlank()
                    || collectionId.length() > 64) {
                throw new IllegalArgumentException("validation case identity is invalid");
            }
            applicationKey = Objects.requireNonNull(
                    applicationKey, "applicationKey").clone();
            value = Objects.requireNonNull(value, "value").clone();
            expected = Objects.requireNonNull(expected, "expected");
        }

        @Override public byte[] applicationKey() { return applicationKey.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    public record Report(
            boolean passed,
            List<String> violations,
            String verdictVectorSha256,
            long invocations,
            long elapsedNanos,
            long allocatedBytes,
            int maximumInputBytes
    ) {
        public Report {
            violations = List.copyOf(violations);
            if (passed != violations.isEmpty() || invocations < 0
                    || elapsedNanos < 0 || allocatedBytes < -1 || maximumInputBytes < 0) {
                throw new IllegalArgumentException("conformance report is inconsistent");
            }
        }
    }

    private record IndexedVerdict(int index, ValidatorVerdict verdict) {
    }

    private static final class Evidence {
        private final List<String> violations = Collections.synchronizedList(new ArrayList<>());
        private long invocations;

        private synchronized void invocation() {
            invocations++;
        }

        private void violation(String value) {
            synchronized (violations) {
                addViolation(violations, value);
            }
        }

        private Report report(
                List<ValidatorVerdict> baseline,
                long elapsedNanos,
                long allocatedBytes,
                List<ValidationCase> cases
        ) {
            List<String> snapshot;
            synchronized (violations) {
                snapshot = List.copyOf(violations);
            }
            int maximum = cases.stream().mapToInt(value -> Math.max(
                    value.applicationKey().length, value.value().length)).max().orElse(0);
            return new Report(snapshot.isEmpty(), snapshot, vectorDigest(baseline),
                    invocations, elapsedNanos, allocatedBytes, maximum);
        }
    }

    private static final class ChildFirstLoader extends URLClassLoader {
        private final String childFirstPackage;

        private ChildFirstLoader(
                URL[] urls,
                ClassLoader parent,
                String childFirstPackage
        ) {
            super(urls, parent);
            this.childFirstPackage = childFirstPackage.endsWith(".")
                    ? childFirstPackage : childFirstPackage + ".";
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith(childFirstPackage)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
}
