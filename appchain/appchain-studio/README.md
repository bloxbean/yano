# Yano App-Chain Studio

Static, release-pinned guided blueprint generation for app-chain projects.
Studio has no backend, telemetry, persistence, or secret inputs. The downloaded
`appchain.yaml` is rendered and semantically validated by the version-matched
`yano appchain` CLI, which remains authoritative.

Build with `./gradlew :appchain-studio:assemble`. Serve the generated
`build/studio` directory with any static HTTP server.

Run the browser-independent Studio checks with
`./gradlew :appchain-studio:testStudio`. Gradle downloads the pinned Node.js
runtime used by this task; a system-wide Node.js installation is not required.
