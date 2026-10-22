package com.example.inventory.service;

public class ProductNotFoundException extends RuntimeException {
  public ProductNotFoundException(long id) {
    super("Product " + id + " was not found");
  }
}
