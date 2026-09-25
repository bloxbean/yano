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
