package com.example.inventory.controller;

import com.example.inventory.model.Product;
import com.example.inventory.model.ProductRequest;
import com.example.inventory.service.ProductService;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

  Logger logger = LoggerFactory.getLogger(ProductController.class);

  private final ProductService service;

  public ProductController(ProductService service) {
    this.service = service;
  }

  @PostMapping
  public Product create(@RequestBody ProductRequest request) {
    return service.create(request);
  }

  @GetMapping
  public List<Product> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public Product get(@PathVariable long id) {
    logger.info("Getting product with id: {}", id);
    return service.get(id);
  }

  @PutMapping("/{id}")
  public Product update(@PathVariable long id, @RequestBody ProductRequest request) {
    return service.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    service.delete(id);
  }

  @PostMapping("/{id}/reservations")
  public Product reserve(@PathVariable long id, @RequestBody Map<String, Integer> body) {
    return service.reserve(id, quantity(body));
  }

  @DeleteMapping("/{id}/reservations")
  public Product release(@PathVariable long id, @RequestBody Map<String, Integer> body) {
    return service.release(id, quantity(body));
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  private int quantity(Map<String, Integer> body) {
    if (body == null || body.get("quantity") == null)
      throw new com.example.inventory.service.InvalidRequestException("quantity is required");
    return body.get("quantity");
  }
}
