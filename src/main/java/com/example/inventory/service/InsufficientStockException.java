package com.example.inventory.service;

public class InsufficientStockException extends RuntimeException {
  public InsufficientStockException(long id) {
    super("Insufficient stock for product " + id);
  }
}
