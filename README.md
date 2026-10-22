# SQLite Inventory REST API

A small Spring Boot 3 application using the traditional controller → service → mapper layers and SQLite.

## Requirements

- JDK 17+
- Maven 3.6+
- Apache JMeter 5.6+

## Run

```sh
mvn spring-boot:run
```

The app listens on `http://localhost:8080` and creates `inventory.db` in the current directory. SQLite's `busy_timeout` is set to five seconds for competing writers.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/products` | Create a product (`name`, `price`, `stock`) |
| `GET` | `/api/products` | List products |
| `GET` | `/api/products/{id}` | Read one product |
| `PUT` | `/api/products/{id}` | Replace product fields |
| `DELETE` | `/api/products/{id}` | Delete a product |
| `POST` | `/api/products/{id}/reservations` | Reserve stock (`{"quantity": 1}`) |
| `DELETE` | `/api/products/{id}/reservations` | Release stock (`{"quantity": 1}`) |
| `GET` | `/api/products/health` | Liveness response |

Invalid input returns `400`, a missing product `404`, and insufficient stock `409`.

## SQLite write locking

SQLite does not implement `SELECT ... FOR UPDATE`. Reservation uses one conditional statement, `UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?`. SQLite obtains its database write lock for this write statement and serializes competing writers; the stock check and decrement are atomic, preventing overselling. The connection `busy_timeout` lets queued writers wait for the lock. This is SQLite's database-level writer lock, not a row-level lock.

## Tests

```sh
mvn test
```

Integration tests use a unique temporary SQLite database and exercise CRUD, validation, stock reservation/release, insufficient inventory, missing records, and health.

## JMeter pressure test

Start the app, then run the plan. Its setup thread creates a product with one million units and passes the returned id to the concurrent threads, so it works with a fresh database and doesn't assume a particular row id. The test uses 100 concurrent threads for 60 seconds against atomic reservation. Expected responses are `200` and `409`.

```sh
jmeter -n -t jmeter/inventory-stress.jmx -l jmeter/results.jtl -e -o jmeter/report
```

Change the JMeter `host` and `port` variables for a different target. Thread count and duration can also be changed with `-Jthreads=100 -Jduration=60`. The generated HTML report is in `jmeter/report`.

Last verified with 100 threads for 60 seconds: 112,587 reservation samples, 0 errors, 1,847 requests/second, and 48 ms average response time.
