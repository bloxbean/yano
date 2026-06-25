package com.bloxbean.cardano.yano.testkit.external;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ExternalApiBoundaryTest {
    private static final Set<Class<?>> EXTERNAL_HELPERS = Set.of(
            HaskellCardanoNodeProcess.class,
            YanoAppProcess.class,
            YanoGenesisFiles.class,
            YanoExternalSyncAssertions.class,
            YanoAdaPotComparator.class
    );

    @Test
    void publicExternalApisDoNotExposeAppQuarkusOrRuntimeInternals() {
        for (Class<?> helper : EXTERNAL_HELPERS) {
            for (Method method : helper.getMethods()) {
                if (!method.getDeclaringClass().equals(helper)
                        || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }

                assertAllowed(method.getReturnType(), helper.getSimpleName() + "." + method.getName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertAllowed(parameterType, helper.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    private static void assertAllowed(Class<?> type, String method) {
        String name = componentType(type).getName();
        assertFalse(name.startsWith("com.bloxbean.cardano.yano.app."),
                method + " exposes app type " + name);
        assertFalse(name.startsWith("com.bloxbean.cardano.yano.runtime."),
                method + " exposes runtime type " + name);
        assertFalse(name.startsWith("io.quarkus."),
                method + " exposes Quarkus type " + name);
        assertFalse(name.startsWith("jakarta."),
                method + " exposes Jakarta type " + name);
    }

    private static Class<?> componentType(Class<?> type) {
        Class<?> current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }
}
