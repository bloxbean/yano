package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DevnetYano} decorates a {@link Yano} by forwarding to it, and {@link Yano} is an
 * interface full of defaults. That combination hides mistakes: a method nobody forwards does not
 * fail to compile, it silently answers empty, false, NONE or -1.
 *
 * <p>It has already happened once. The archive surface went unforwarded, and devnet got a
 * projection that installed no contributors and recorded no genesis while reporting itself
 * healthy. Only {@code chainstateRocksAccess} threw, and only because the projection refuses to
 * start without it; the rest degraded in silence.
 *
 * <p>So this asserts the decorator forwards everything, and requires anything deliberately not
 * forwarded to be named below with a reason. The point is not the current list - it is that
 * adding a method to Yano fails here until someone decides which side of the line it belongs on.
 */
class DevnetYanoForwardingTest {

    /**
     * Methods that must not be forwarded, and why.
     *
     * <p>Keep this list short and justified. A name added here to make the test pass is the
     * failure mode this test exists to prevent.
     */
    private static final Set<String> DELIBERATELY_NOT_FORWARDED = Set.of(
            // The decorator's entire purpose: it supplies its own DevnetControl.
            "devnetControl()",
            // Their Yano defaults call lifecycle(), which is forwarded - so forwarding these
            // would be a second path to the same place.
            "start()",
            "stop()");

    /**
     * Name plus parameter types, because a name alone is not an identity.
     *
     * <p>Comparing names would let an overload of an already-forwarded method pass: the name is
     * present in the decorator, so the new signature falls through to its Yano default while the
     * test stays green. That is exactly the failure this class exists to catch, so the comparison
     * has to be as precise as the dispatch it stands in for.
     */
    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes())
                // Fully qualified, because two overloads can take same-named types from
                // different packages. A simple name would make those signatures equal, and one
                // of them would fall through to its Yano default with this test still green.
                .map(Class::getName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    @Test
    void everyYanoMethodIsForwardedOrExplicitlyExcluded() {
        List<String> declared = Arrays.stream(Yano.class.getMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() != Object.class)
                .map(DevnetYanoForwardingTest::signature)
                .distinct()
                .sorted()
                .toList();

        Class<?> decorator = decoratorClass();
        Set<String> overridden = Arrays.stream(decorator.getDeclaredMethods())
                .map(DevnetYanoForwardingTest::signature)
                .collect(Collectors.toSet());

        List<String> unforwarded = declared.stream()
                .filter(name -> !overridden.contains(name))
                .filter(name -> !DELIBERATELY_NOT_FORWARDED.contains(name))
                .toList();

        assertThat(unforwarded)
                .as("DevnetYano must forward these to its delegate, or DELIBERATELY_NOT_FORWARDED"
                        + " must say why not. Falling through to a Yano default is not a decision;"
                        + " it is a silent one.")
                .isEmpty();
    }

    @Test
    void theExclusionListDoesNotOutliveTheMethodsItNames() {
        // An exclusion for a method that no longer exists is stale permission: it would keep
        // covering a future method that happens to reuse the name.
        Set<String> declared = Arrays.stream(Yano.class.getMethods())
                .map(DevnetYanoForwardingTest::signature)
                .collect(Collectors.toSet());

        assertThat(declared).containsAll(DELIBERATELY_NOT_FORWARDED);
    }

    private static Class<?> decoratorClass() {
        return Arrays.stream(YanoDevnetAssembly.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("DevnetYano"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "DevnetYano not found; if it was renamed, this test must follow it"));
    }
}
