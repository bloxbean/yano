package com.bloxbean.cardano.yano.app.api;

/**
 * OpenAPI document groups (ADR-040).
 *
 * <p>Each constant is the name of a SmallRye OpenAPI <em>scan profile</em>
 * extension. Annotating a resource class (or a single operation) with
 * {@code @Extension(name = ApiGroup.CORE, value = "")} places its operations
 * in the named OpenAPI document whose {@code scan-profiles} lists that
 * profile (see {@code quarkus.smallrye-openapi."<name>".scan-profiles} in
 * {@code application.yml}). Swagger UI renders one drop-down entry per
 * document.</p>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Every JAX-RS resource class must carry at least one group; an
 *       operation without a profile is only visible in the unnamed
 *       "All APIs" document. {@code ApiGroupOpenApiTest} fails the build
 *       when an operation is reachable from no group.</li>
 *   <li>A method-level {@code @Extension} <strong>replaces</strong> the
 *       class-level set for that operation; repeat every intended group on
 *       the method.</li>
 *   <li>The extension is stripped from the emitted documents; API consumers
 *       never see it. Routes are not affected in any way.</li>
 * </ul>
 */
public final class ApiGroup {

    private static final String PREFIX = "x-smallrye-profile-";

    /** Chain/ledger queries and transaction submission for application developers. */
    public static final String CORE = PREFIX + "core";

    /** App-chain surface: chain-scoped messages, state proofs, effects, plugin domain routes. */
    public static final String APP_CHAIN = PREFIX + "app-chain";

    /** Devnet-only controls: rollback, snapshots, faucet, time travel. */
    public static final String DEVNET = PREFIX + "devnet";

    /** Operator surface: node lifecycle, plugin operations, diagnostics. */
    public static final String ADMIN = PREFIX + "admin";

    private ApiGroup() {
    }
}
