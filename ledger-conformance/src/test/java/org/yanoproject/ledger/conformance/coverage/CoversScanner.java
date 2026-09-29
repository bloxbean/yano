package org.yanoproject.ledger.conformance.coverage;

import org.yanoproject.ledger.rules.fixtures.conformance.Covers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds {@link Covers} annotations on test classes and methods in compiled test-class directories.
 *
 * <p>This module's own test classes are loaded through the test class loader; other modules' directories
 * ({@code conformance.scan.dirs}, e.g. ledger-rules' tests from Phase 3 on) through a child class loader. A class
 * that cannot be loaded or introspected is skipped and reported, never fatal: the scanner is looking for
 * annotations, not running tests.</p>
 */
public final class CoversScanner {

    /**
     * One annotated test.
     *
     * @param constructor the {@code RULE.Constructor} the test covers
     * @param test        {@code SimpleClassName#method}, or the class name for a class-level annotation
     * @param className   the test class's binary name
     * @param versions    the protocol versions the test validates at ({@link Covers#pv()}); empty when unstated
     */
    public record Covering(String constructor, String test, String className, List<Integer> versions) {

        public Covering {
            versions = List.copyOf(versions);
        }
    }

    /** The scan's result: the coverings, and the classes that could not be inspected. */
    public record Result(List<Covering> coverings, List<String> skipped) {
    }

    private CoversScanner() {
    }

    /**
     * @param ownClasses   this module's test-class directory (loaded with {@code loader})
     * @param otherClasses other modules' test-class directories (may not exist yet)
     */
    public static Result scan(Path ownClasses, List<Path> otherClasses, ClassLoader loader) {
        List<Covering> coverings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        scanDirectory(ownClasses, loader, coverings, skipped);
        for (Path dir : otherClasses) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (URLClassLoader child = new URLClassLoader(new URL[]{dir.toUri().toURL()}, loader)) {
                scanDirectory(dir, child, coverings, skipped);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(dir.toString(), e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        coverings.sort(Comparator.comparing(Covering::constructor).thenComparing(Covering::test));
        return new Result(List.copyOf(coverings), List.copyOf(skipped));
    }

    private static List<Integer> versions(Covers covers) {
        return Arrays.stream(covers.pv()).boxed().toList();
    }

    private static void scanDirectory(Path root, ClassLoader loader, List<Covering> coverings, List<String> skipped) {
        List<String> classNames;
        try (Stream<Path> files = Files.walk(root)) {
            classNames = files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString())
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                for (Covers covers : type.getDeclaredAnnotationsByType(Covers.class)) {
                    coverings.add(new Covering(covers.value(), type.getSimpleName(), className, versions(covers)));
                }
                for (Method method : type.getDeclaredMethods()) {
                    for (Covers covers : method.getDeclaredAnnotationsByType(Covers.class)) {
                        coverings.add(new Covering(covers.value(), type.getSimpleName() + "#" + method.getName(),
                                className, versions(covers)));
                    }
                }
            } catch (ClassNotFoundException | LinkageError e) {
                skipped.add(className + " (" + e.getClass().getSimpleName() + ")");
            }
        }
    }
}
