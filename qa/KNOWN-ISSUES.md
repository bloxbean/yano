# Known issues

A failing or timed-out test that matches a row here is reported as **known**
instead of failed, and does not fail the run. Keep this list short: add a row
only when the failure has a tracked issue, and remove it when the fix lands.

- **Test**: a test id from `qa/release-qa.sh --list`.
- **Match**: a regular expression searched in the verdict reason and the test
  log. Leave it empty to match any failure of that test.

SDK compatibility cases have their own list in `compat-tests/KNOWN-FAILS.md`.

| Test | Match | Issue | Note |
|---|---|---|---|
| `e2e-jvm` | `(?s)\Asmoke\n.*Summary: passed=\d+, failed=1, .*devnet fund guard: expected \[200, 400, 404, 409\], got 500` | [#184](https://github.com/bloxbean/yano/issues/184) | Smoke `devnet fund guard`: a malformed address returns 500 instead of 400. Matches only when it is the sole smoke failure and the functional suites pass. |
| `e2e-native` | `(?s)\Asmoke\n.*Summary: passed=\d+, failed=1, .*devnet fund guard: expected \[200, 400, 404, 409\], got 500` | [#184](https://github.com/bloxbean/yano/issues/184) | Smoke `devnet fund guard`: a malformed address returns 500 instead of 400. Matches only when it is the sole smoke failure and the functional suites pass. |
