package com.example.inventory.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.inventory.model.Product;
import com.example.inventory.service.InsufficientStockException;
import com.example.inventory.service.InvalidRequestException;
import com.example.inventory.service.ProductNotFoundException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the Akka path directly, without going through the HTTP layer. */
@SpringBootTest
class ProductActorIntegrationTest {
  private static final Path DB =
      Path.of(
          System.getProperty("java.io.tmpdir"),
          "inventory-actor-test-" + UUID.randomUUID() + ".db");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
  }

  @Autowired ProductActorCoordinator coordinator;
  @Autowired JdbcTemplate jdbc;
  private long productId;

  @BeforeEach
  void seedProduct() {
    jdbc.update("DELETE FROM products");
    productId =
        jdbc.queryForObject(
            "INSERT INTO products(name, price, stock) VALUES ('Keyboard', 79.90, 5) RETURNING id",
            Long.class);
  }

  @Test
  void concurrentReservationsThroughTheActorNeverOversell() {
    AtomicInteger reserved = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    IntStream.range(0, 20)
        .parallel()
        .forEach(
            ignored -> {
              try {
                coordinator.reserve(productId, 1);
                reserved.incrementAndGet();
              } catch (InsufficientStockException expected) {
                rejected.incrementAndGet();
              }
            });

    assertEquals(5, reserved.get());
    assertEquals(15, rejected.get());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId));
  }

  @Test
  void readsAndStockMutationsShareTheActorsMailbox() {
    assertEquals(1, coordinator.list().size());
    assertEquals("Keyboard", coordinator.get(productId).name());

    Product afterReserve = coordinator.reserve(productId, 2);
    assertEquals(3, afterReserve.stock());

    Product afterRelease = coordinator.release(productId, 3);
    assertEquals(6, afterRelease.stock());

    // Only successful stock commands are counted.
    assertEquals(2, coordinator.status().stockOperations());
    assertEquals("", coordinator.status().lastFailure());
  }

  @Test
  void actorRejectsInvalidQuantitiesAndMissingProducts() {
    assertThrows(InvalidRequestException.class, () -> coordinator.reserve(productId, 0));
    assertThrows(InvalidRequestException.class, () -> coordinator.release(productId, -1));
    assertThrows(InsufficientStockException.class, () -> coordinator.reserve(productId, 6));

    // A rejected command is recorded as the last failure.
    assertEquals("Insufficient stock for product " + productId, coordinator.status().lastFailure());

    assertThrows(ProductNotFoundException.class, () -> coordinator.get(Long.MAX_VALUE));
    assertThrows(ProductNotFoundException.class, () -> coordinator.reserve(Long.MAX_VALUE, 1));
    assertThrows(ProductNotFoundException.class, () -> coordinator.release(Long.MAX_VALUE, 1));

    // The actor keeps serving commands after every rejection.
    assertEquals(5, coordinator.get(productId).stock());
  }
}
