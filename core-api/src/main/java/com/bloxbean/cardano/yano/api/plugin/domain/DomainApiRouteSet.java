package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared validation and dispatch ordering for one domain API's exact route set.
 *
 * <p>Both plugin tests and host runtimes should use this class so a route set
 * accepted before deployment has the same duplicate, ambiguity, and
 * specificity rules in production.</p>
 */
public final class DomainApiRouteSet {
    private static final Comparator<CompiledRoute> DISPATCH_ORDER =
            Comparator.comparing((CompiledRoute route) -> route.route().method())
                    .thenComparing(CompiledRoute::segments, DomainApiRouteSet::compareSegments)
                    .thenComparing(route -> route.route().template())
                    .thenComparing(route -> route.route().routeId());

    private DomainApiRouteSet() {
    }

    /**
     * Validate and defensively snapshot {@code declared}, returning immutable
     * deterministic dispatch order. At the first differing literal/variable
     * position, the literal route precedes the variable route. Parameter names
     * do not affect precedence.
     *
     * @throws IllegalArgumentException for too many routes, a null entry, a
     *                                  duplicate route id, or structurally
     *                                  ambiguous routes for the same method
     * @throws NullPointerException     when {@code declared} is null
     */
    public static List<DomainApiRoute> validateAndOrder(
            Collection<? extends DomainApiRoute> declared
    ) {
        Objects.requireNonNull(declared, "declared");
        if (declared.size() > DomainApi.MAX_ROUTES) {
            throw new IllegalArgumentException(
                    "domain API must declare at most " + DomainApi.MAX_ROUTES + " routes");
        }

        Map<String, DomainApiRoute> routeIds = new HashMap<>();
        List<CompiledRoute> compiled = new ArrayList<>(declared.size());
        for (DomainApiRoute route : declared) {
            if (route == null) {
                throw new IllegalArgumentException("domain API routes must not contain null entries");
            }
            if (routeIds.putIfAbsent(route.routeId(), route) != null) {
                throw new IllegalArgumentException(
                        "duplicate domain API route id '" + route.routeId() + "'");
            }
            compiled.add(new CompiledRoute(route));
        }

        for (int left = 0; left < compiled.size(); left++) {
            for (int right = left + 1; right < compiled.size(); right++) {
                rejectStructuralCollision(compiled.get(left), compiled.get(right));
            }
        }
        compiled.sort(DISPATCH_ORDER);
        return compiled.stream().map(CompiledRoute::route).toList();
    }

    private static void rejectStructuralCollision(CompiledRoute left, CompiledRoute right) {
        if (left.route().method() != right.route().method()
                || left.segments().size() != right.segments().size()) {
            return;
        }
        for (int index = 0; index < left.segments().size(); index++) {
            String a = left.segments().get(index);
            String b = right.segments().get(index);
            boolean aParameter = isParameter(a);
            boolean bParameter = isParameter(b);
            if (aParameter != bParameter || (!aParameter && !a.equals(b))) {
                return;
            }
        }
        throw new IllegalArgumentException("structurally duplicate domain API routes: "
                + left.route().method() + " " + left.route().template()
                + " and " + right.route().template());
    }

    private static int compareSegments(List<String> left, List<String> right) {
        int sharedSegments = Math.min(left.size(), right.size());
        for (int index = 0; index < sharedSegments; index++) {
            String a = left.get(index);
            String b = right.get(index);
            boolean aParameter = isParameter(a);
            boolean bParameter = isParameter(b);
            if (aParameter != bParameter) {
                return aParameter ? 1 : -1;
            }
            if (!aParameter) {
                int comparison = a.compareTo(b);
                if (comparison != 0) {
                    return comparison;
                }
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static boolean isParameter(String segment) {
        return segment.startsWith("{");
    }

    private record CompiledRoute(
            DomainApiRoute route,
            List<String> segments
    ) {
        private CompiledRoute(DomainApiRoute route) {
            this(route, List.of(route.template().split("/", -1)));
        }
    }
}
