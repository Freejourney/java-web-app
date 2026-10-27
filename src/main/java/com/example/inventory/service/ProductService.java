package com.example.inventory.service;

import com.example.inventory.mapper.ProductMapper;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductRequest;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
  private final ProductMapper mapper;

  public ProductService(ProductMapper mapper) {
    this.mapper = mapper;
  }

  public Product create(ProductRequest request) {
    validate(request);
    return mapper.insert(request);
  }

  public List<Product> list() {
    return mapper.findAll();
  }

  public Product get(long id) {
    return mapper.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
  }

  public Product update(long id, ProductRequest request) {
    validate(request);
    if (mapper.update(id, request) == 0) throw new ProductNotFoundException(id);
    return get(id);
  }

  public void delete(long id) {
    if (mapper.delete(id) == 0) throw new ProductNotFoundException(id);
  }

  public Product reserve(long id, int quantity) {
    validateQuantity(quantity);
    if (mapper.reserveStock(id, quantity) == 0) {
      if (mapper.findById(id).isEmpty()) throw new ProductNotFoundException(id);
      throw new InsufficientStockException(id);
    }
    return get(id);
  }

  public Product release(long id, int quantity) {
    validateQuantity(quantity);
    if (mapper.releaseStock(id, quantity) == 0) {
      if (mapper.findById(id).isEmpty()) throw new ProductNotFoundException(id);
      throw new InvalidRequestException("released stock exceeds the supported maximum");
    }
    return get(id);
  }

  private void validate(ProductRequest request) {
    if (request == null
        || request.name() == null
        || request.name().isBlank()
        || request.price() == null
        || request.price().compareTo(BigDecimal.ZERO) < 0
        || request.stock() < 0) {
      throw new InvalidRequestException(
          "name must be non-empty, price and stock must be non-negative");
    }
  }

  private void validateQuantity(int quantity) {
    if (quantity <= 0) throw new InvalidRequestException("quantity must be greater than zero");
  }
}
