# SQLite Inventory REST API

A small Spring Boot 3 application using the traditional controller → service → mapper layers and SQLite.

## Requirements

- JDK 17+
- Maven 3.6+
- Apache JMeter 5.6+

## Dependencies

The build uses Akka 2.6.20 (`akka-actor-typed`, `akka-slf4j`) for the actor layer. Akka 2.6.x is
licensed under the Business Source License 1.1: free for development and non-production use, but a
commercial license is required for production. If that is a constraint, Apache Pekko is the
Apache-2.0 fork with the same API surface.

## Run

```sh
mvn spring-boot:run
```

The app listens on `http://localhost:8080` and creates `inventory.db` in the current directory. SQLite's `busy_timeout` is set to five seconds for competing writers.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/products` | Create a product (`name`, `price`, `stock`) |
| `GET` | `/api/products` | List products (through the actor) |
| `GET` | `/api/products/{id}` | Read one product (through the actor) |
| `PUT` | `/api/products/{id}` | Replace product fields |
| `DELETE` | `/api/products/{id}` | Delete a product |
| `POST` | `/api/products/{id}/reservations` | Reserve stock (`{"quantity": 1}`, through the actor) |
| `DELETE` | `/api/products/{id}/reservations` | Release stock (`{"quantity": 1}`, through the actor) |
| `GET` | `/api/products/health` | Liveness response |
| `GET` | `/api/products/actor` | Actor diagnostics (stock operations, last failure) |

Invalid input returns `400`, a missing product `404`, insufficient stock `409`, and an unreachable actor `503`.

## Akka actor layer

Reads and stock mutations run through a single typed Akka actor instead of calling `ProductService`
directly. Create, update and delete keep using the service because they are independent
single-statement writes; the actor owns the traffic whose correctness depends on ordering.

- `ProductActor` processes one command at a time, so a stock read-modify-write cannot interleave
  with another stock command. This mirrors the atomic conditional `UPDATE`, but adds serialization
  of whole commands, so `POST /reservations` answers `200` with the post-reservation row rather than a
  value a competing writer could have invalidated in between.
- Commands and replies are sealed records in `ProductCommands`. Rejections travel back as
  `StockReply.Failure` data rather than a thrown exception, which keeps the actor alive and lets the
  controller rethrow the original API exception.
- `ProductActorCoordinator` bridges the asynchronous ask pattern to the blocking Spring MVC
  controllers with a five second timeout; a timeout or interrupted wait raises
  `ProductActorUnavailableException`, which maps to `503`.
- The actor is spawned on `akka.actor.default-blocking-io-dispatcher`, a dedicated thread pool. The
  SQLite mapper waits on a database lock while a competing writer holds the write lock, and parking
  those waits off the Akka default dispatcher keeps other actor work from starving.
- The bean is supervised with `SupervisorStrategy.restart()`, so an unexpected mapper failure
  restarts the actor instead of stopping it permanently.
- `AkkaConfiguration` publishes the actor system and the actor as Spring beans;
  `application.conf` routes Akka logging through the SLF4J backend Spring Boot already configures, so
  Spring keeps reading `application.properties`.

`GET /api/products/actor` exposes the actor's counters to verify the actor path is live:

```sh
curl http://localhost:8080/api/products/actor
# {"status":"UP","lastFailure":"","stockOperations":0}
```

## SQLite write locking

SQLite does not implement `SELECT ... FOR UPDATE`. Reservation uses one conditional statement, `UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?`. SQLite obtains its database write lock for this write statement and serializes competing writers; the stock check and decrement are atomic, preventing overselling. The connection `busy_timeout` lets queued writers wait for the lock. This is SQLite's database-level writer lock, not a row-level lock.

## Tests

```sh
mvn test
```

Integration tests use a unique temporary SQLite database and exercise CRUD, validation, stock reservation/release, insufficient inventory, missing records, and health. `ProductActorIntegrationTest` additionally drives the actor path directly, including a concurrent reservation burst that must not oversell.

## GitHub Actions CI/CD

`.github/workflows/ci-cd.yml` runs `mvn verify` for pull requests targeting `main`, pushes to
`main`, version tags beginning with `v`, and manual runs. After tests pass, pushes and tag builds
create and publish a Docker image to GitHub Container Registry (`ghcr.io/<owner>/<repository>`).
The default branch also receives the `latest` tag; every build gets a commit SHA tag.

The workflow uses the repository's `GITHUB_TOKEN`, so no registry password secret is needed. Ensure
the repository's Actions setting allows workflows to read and write packages. The workflow publishes
the image; connecting it to a running server still requires a deployment target and its credentials.

## JMeter pressure test

Start the app, then run the plan. Each virtual user creates its own product, exercises each of the eight endpoints in sequence, and deletes its product. Each sampler has a distinct label and an HTTP status assertion, so JMeter's Summary Report and HTML report show results for every API separately. The test uses 100 concurrent threads for 60 seconds.

```sh
jmeter -n -t jmeter/inventory-stress.jmx -l jmeter/results.jtl -e -o jmeter/report
```

Change the JMeter `host` and `port` variables for a different target. Thread count and duration can also be changed with `-Jthreads=100 -Jduration=60`. The generated HTML report is in `jmeter/report`.

### Last all-API run

100 threads, 60 seconds, 132,921 total samples, 0% errors. The per-endpoint values below are from the generated JMeter report; p95 is the 95th percentile response time.

| API | Samples | Throughput/s | Avg ms | p95 ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| `POST /api/products` create | 16,660 | 277.5 | 48.4 | 146 | 0% |
| `GET /api/products` list | 16,647 | 280.1 | 42.7 | 133 | 0% |
| `GET /api/products/{id}` read | 16,637 | 280.0 | 44.1 | 142 | 0% |
| `PUT /api/products/{id}` update | 16,620 | 279.5 | 51.6 | 150 | 0% |
| `POST /api/products/{id}/reservations` reserve | 16,604 | 279.4 | 50.1 | 148 | 0% |
| `DELETE /api/products/{id}/reservations` release | 16,593 | 279.1 | 48.7 | 147 | 0% |
| `GET /api/products/health` health | 16,580 | 279.2 | 0.2 | 1 | 0% |
| `DELETE /api/products/{id}` delete | 16,580 | 278.7 | 45.2 | 144 | 0% |

## Isolated endpoint and light end-to-end plan

`jmeter/inventory-isolated-api-stress.jmx` runs each API in its own sequential thread group. It seeds a persistent fixture first, then runs single-endpoint load against existing data. Create uses a 100-thread concurrent insert burst; delete removes those uniquely created products with 100 concurrent delete requests. The final lifecycle group runs only 10 end-to-end users. Defaults are 100 threads for 20 seconds per sustained endpoint group, with a 5-second ramp; adjust them with `-Jthreads`, `-JisolatedDuration`, `-JrampTime`, `-JseedThreads`, and `-JfullChainThreads`.

```sh
jmeter -n -t jmeter/inventory-isolated-api-stress.jmx \
  -l jmeter/isolated-results.jtl -e -o jmeter/report-isolated
```

Latest isolated run: 786,664 samples across setup, endpoint groups, and the light end-to-end group; 0 errors. Each endpoint group's isolated result is in the [HTML report](./jmeter/report-isolated/index.html) and summarized below (p95 is 95th percentile; create/delete are concurrent bursts, other API groups are sustained for 20 seconds).

| API | Samples | Throughput/s | Avg ms | p95 ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| `POST /api/products` create | 100 | 10.1 | 3.1 | 5 | 0% |
| `GET /api/products` list | 61,697 | 3,075.5 | 28.3 | 162 | 0% |
| `GET /api/products/{id}` read | 274,517 | 13,697.8 | 6.3 | 23 | 0% |
| `PUT /api/products/{id}` update | 57,857 | 2,879.9 | 30.3 | 126 | 0% |
| `POST /api/products/{id}/reservations` reserve | 30,984 | 1,538.2 | 56.6 | 174 | 0% |
| `DELETE /api/products/{id}/reservations` release | 32,498 | 1,604.3 | 54.0 | 176 | 0% |
| `GET /api/products/health` health | 328,830 | 16,416.1 | 5.3 | 6 | 0% |
| `DELETE /api/products/{id}` delete | 100 | 10.1 | 2.5 | 4 | 0% |
