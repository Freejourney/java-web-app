package com.example.inventory.controller;

import com.example.inventory.service.InsufficientStockException;
import com.example.inventory.service.InvalidRequestException;
import com.example.inventory.service.ProductNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ProductNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> notFound(RuntimeException ex) {
    return Map.of("error", ex.getMessage());
  }

  @ExceptionHandler(InsufficientStockException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, String> conflict(RuntimeException ex) {
    return Map.of("error", ex.getMessage());
  }

  @ExceptionHandler(InvalidRequestException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> badRequest(RuntimeException ex) {
    return Map.of("error", ex.getMessage());
  }
}
