package com.example.inventory.actor;

/** Signals that the product actor could not answer a request, for example after an ask timeout. */
public class ProductActorUnavailableException extends RuntimeException {

  public ProductActorUnavailableException(String message) {
    super(message);
  }

  public ProductActorUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
