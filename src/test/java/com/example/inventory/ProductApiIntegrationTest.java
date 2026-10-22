package com.example.inventory;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductApiIntegrationTest {
  private static final Path DB =
      Path.of(System.getProperty("java.io.tmpdir"), "inventory-test-" + UUID.randomUUID() + ".db");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  private long productId;

  @BeforeEach
  void createProduct() throws Exception {
    jdbc.update("DELETE FROM products");
    String response =
        mvc.perform(
                post("/api/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Keyboard\",\"price\":79.90,\"stock\":5}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    productId =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(response)
            .get("id")
            .asLong();
  }

  @Test
  void concurrentReservationsNeverOversell() {
    AtomicInteger reserved = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    IntStream.range(0, 20)
        .parallel()
        .forEach(
            ignored -> {
              try {
                int code =
                    mvc.perform(
                            post("/api/products/{id}/reservations", productId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":1}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (code == 200) reserved.incrementAndGet();
                else if (code == 409) rejected.incrementAndGet();
                else throw new AssertionError("Unexpected response: " + code);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    org.junit.jupiter.api.Assertions.assertEquals(5, reserved.get());
    org.junit.jupiter.api.Assertions.assertEquals(15, rejected.get());
  }

  @Test
  void createListReadUpdateDeleteAndNotFound() throws Exception {
    mvc.perform(get("/api/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
    mvc.perform(get("/api/products/{id}", productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Keyboard"));
    mvc.perform(
            put("/api/products/{id}", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ergo Keyboard\",\"price\":99.5,\"stock\":8}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Ergo Keyboard"))
        .andExpect(jsonPath("$.stock").value(8));
    mvc.perform(delete("/api/products/{id}", productId)).andExpect(status().isNoContent());
    mvc.perform(get("/api/products/{id}", productId)).andExpect(status().isNotFound());
  }

  @Test
  void reservesAndReleasesStockAtomically() throws Exception {
    mvc.perform(
            post("/api/products/{id}/reservations", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stock").value(2));
    mvc.perform(
            post("/api/products/{id}/reservations", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3}"))
        .andExpect(status().isConflict());
    mvc.perform(
            delete("/api/products/{id}/reservations", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stock").value(4));
  }

  @Test
  void validatesInputAndHandlesHealthAndMissingReservation() throws Exception {
    mvc.perform(get("/api/products/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"price\":-1,\"stock\":-1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/products/999999/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/products/{id}/reservations", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest());
  }
}
