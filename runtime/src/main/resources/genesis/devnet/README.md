# Devnet Genesis Resource Copy

This directory is the runtime/testkit copy of the application devnet profile
files in `app/config/network/devnet`.

Update both copies together when changing the default devnet protocol version,
genesis protocol parameters, cost models, bootstrap values, or devnet signing
fixtures. The files are duplicated intentionally so published runtime and
testkit artifacts can start the default devnet without depending on the app
source tree or build-time copy tasks.

The `*.skey` files are public non-production devnet fixtures. Do not reuse them
for public networks or real funds.

The `pv10/` compatibility overlay is intentionally not copied here. The default
runtime/testkit devnet starts directly at protocol 11, matching the regular
distribution devnet profile.
