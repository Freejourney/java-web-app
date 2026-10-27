# Repository bug audit

Audit date: 2026-10-26

## Executive summary

The repository is a small Java 17 / Spring Boot 3.3.5 REST service backed by SQLite and accessed through Spring JDBC. The review found one reproducible correctness bug: releasing enough stock could exceed Java's supported `int` stock range. An atomic upper bound has been added, with a regression test. No other verifiable bug or security vulnerability was confirmed from the inspected source and configuration.

## Repository map and system boundaries

- `src/main/java/com/example/inventory/InventoryApplication.java`: Spring Boot entry point.
- `controller/`: REST routes and exception-to-HTTP mapping.
- `service/`: input validation, business rules, stock operations.
- `mapper/`: parameterized SQL through `JdbcTemplate`.
- `model/`: immutable product and request records.
- `src/main/resources/schema.sql`: SQLite schema; `application.properties`: datasource and server settings.
- `src/test/`: MockMvc integration tests against a unique temporary SQLite database.
- `jmeter/*.jmx`: concurrent and isolated API load plans.
- `pom.xml`, `mise.toml`: Maven / Java dependency and tool configuration.
- `README.md`: API, run instructions, SQLite locking rationale, and performance results.

The HTTP API is the system boundary. The main data path is controller → service → mapper → SQLite. There is no authentication layer, external service integration, migration framework, CI workflow, or dependency lockfile in the repository. The API should therefore be deployed behind an appropriate trusted boundary if product data is not intended to be public; this review cannot infer the deployment topology.

## Findings

### INV-001 — Stock release could exceed representable stock

- **Severity:** Medium (data correctness)
- **Category:** Boundary / functional
- **Component:** Reservation release
- **Files:** `ProductMapper.java`, `ProductService.java`
- **Before:** `releaseStock` unconditionally added the positive request quantity. Stock is exposed and read as a Java `int`, but the SQLite column can hold a larger integer. A release near `Integer.MAX_VALUE` could therefore persist a value that cannot be represented by the API model and JDBC `getInt`.
- **Expected:** A release must be atomic and must leave stock within `[0, Integer.MAX_VALUE]`; rejected requests must not mutate data.
- **Root cause / impact:** The update guarded against negative stock only indirectly (all releases are positive) and had no upper bound. Extreme but valid integer input could corrupt the service's stock representation, yielding inconsistent responses and future behavior.
- **Fix:** Add an atomic SQL predicate `stock <= Integer.MAX_VALUE - quantity`. On a failed update, distinguish a missing product (`404`) from a range overflow (`400`).
- **Regression verification:** Added `releaseCannotOverflowSupportedStockRange`; it sets stock to `Integer.MAX_VALUE - 1`, tries to release two units, expects `400`, and checks the row is unchanged.
- **Reproduction:** Set a product's stock to `2147483646`, then call `DELETE /api/products/{id}/reservations` with `{"quantity":2}`. The fixed API rejects the request without changing the row.

## Reviewed areas and prioritization

| Priority | Area | Result |
| --- | --- | --- |
| P1 | SQL injection / query construction | Queries use placeholders; no injectable SQL path found. |
| P1 | Reservation concurrency | Reserve remains a single conditional update; existing concurrent oversell test covers the core invariant. |
| P1 | Input / missing-resource handling | Service checks product fields and positive quantities; API exception mappings cover known domain errors. |
| P2 | Integer boundaries | INV-001 fixed; create/update stock and quantity are bounded by Java `int` JSON fields. |
| P2 | Configuration / startup | SQLite busy timeout is configured; schema initializes at startup. No TLS/auth configuration is present in this standalone app. |
| P2 | Dependencies / supply chain | Versions are declared in Maven, but dependency resolution and vulnerability scanning could not run in this environment. No vulnerability is asserted. |
| P3 | Load plans / docs | Both JMeter plans and their XML structure were included in static review. Reported historical performance figures were not rerun. |

## Validation

- Shell-based checks (including `git diff --check` and XML parsing): not run; the command environment fails before execution because Apple's command-line developer tools are missing.
- Maven tests and dependency scanning: **not run**. The environment has no usable Java runtime (`java -version` fails with “Unable to locate a Java Runtime”), and Maven is unavailable. Install/configure the JDK and Maven, then run `mvn test` and a dependency vulnerability scan before release.
- No performance benchmark was run.

## Preventive recommendations

1. Add CI for `mvn test`, formatting verification, and dependency vulnerability scanning.
2. Keep storage and API numeric ranges aligned; retain database-level range constraints if the schema evolves.
3. Add authentication and transport security at the service or deployment boundary when exposing this API beyond a trusted local environment.
4. Treat load-test summaries as generated evidence with a recorded commit, configuration, and environment.

## Audit limitations

This is a source-level audit of the repository contents available on 2026-10-26. Runtime behavior, resolved transitive dependency versions, deployed security controls, and historical JMeter output could not be independently verified here. “No other issue found” is not a proof that no latent issue exists.
