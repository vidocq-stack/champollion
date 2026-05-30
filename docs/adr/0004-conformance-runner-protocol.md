# ADR 0004 — Google `conformance_test_runner` protocol + `--failure_list`

## Status

Accepted — 2026-05-22.

## Context

Google's `conformance_test_runner` (compiled via CMake from
`protocolbuffers/protobuf` v35.0, cf. M5.4) communicates with the tested
implementation via stdin/stdout pipe. The exact protocol is not trivially
documented and was reverse-engineered during M5.4.

## Decision — Exact Protocol

1. **Length-prefix LITTLE-ENDIAN, not big-endian** as the documentation
   might suggest. The runner writes `[4 bytes length LE][protobuf
   ConformanceRequest bytes]` to the child's stdin. Champollion does the reverse
   on output.

2. **`fork_pipe_runner`** forks the wrapper for each test (not long-lived).
   The JVM starts in ~70 ms with AppCDS — fast enough for 2600 sequential
   tests.

3. **Separator tag `--` not supported.** The modern runner only accepts
   `runner [options] <program>` (program last). We use a bash wrapper
   `target/run-runner.sh` that does `exec java -cp …`.

4. **Complete classpath via `mvn dependency:build-classpath`.** The `java -jar
   tck-jar` doesn't load external deps (NoClassDefFoundError on
   `Message`, `jakarta.json-api`, etc.). The script generates
   `target/conformance-classpath.txt` and uses it for `java -cp`.

5. **`--failure_list FILE`** — text file with one test name per line,
   `#` for comments. Listed tests = expected failures, counted
   separately in output `CONFORMANCE SUITE PASSED: N successes, …
   M expected failures, 0 unexpected failures`.

## Consequences

**Positives**:
- Stateless, reproducible Java wrapper. Single command
  `./run-official-conformance-protobuf.sh smoke` → exit 0 = 100% PASS.
- Failure-list is versioned — explicit "expected failures" review
  on each PR (`git diff conformance-failure-list.txt`).
- Degraded mode (without `CONFORMANCE_TEST_RUNNER` env var) remains operational
  — useful for contributors who don't have the runner built locally.

**Negatives**:
- The `--failure_list` format is not documented on Google's side; its
  cross-version stability is not guaranteed. Mitigation: monthly re-run + alert on
  divergence.

## Rejected Alternatives

| Option | Why rejected |
|---|---|
| Big-endian length-prefix | Proven wrong by the 30s timeout observed in M5.4 |
| Long-lived JVM process | `fork_pipe_runner` doesn't support it; it forks for each test |
| Fat jar with all deps | Heavy (~30 MB), unnecessary given `java -cp` |
| `failure_list` inline in wrapper | Would lose explicit review via git diff |
