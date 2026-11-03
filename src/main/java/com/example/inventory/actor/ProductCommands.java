package com.example.inventory.actor;

import akka.actor.typed.ActorRef;
import com.example.inventory.model.Product;
import java.util.List;

/** Commands accepted by the {@link ProductActor}. */
public sealed interface ProductCommands {

  /** Lists every product. */
  record ListProducts(ActorRef<ListReply> replyTo) implements ProductCommands {}

  /** Reads a single product. */
  record GetProduct(long id, ActorRef<ProductReply> replyTo) implements ProductCommands {}

  /** Stock mutations share one shape so the actor can handle them with a single handler. */
  sealed interface StockCommand extends ProductCommands {
    long id();

    int quantity();

    ActorRef<StockReply> replyTo();
  }

  /** Decrements stock, failing when the requested quantity is unavailable. */
  record ReserveStock(long id, int quantity, ActorRef<StockReply> replyTo)
      implements StockCommand {}

  /** Restores previously reserved stock. */
  record ReleaseStock(long id, int quantity, ActorRef<StockReply> replyTo)
      implements StockCommand {}

  /** Reads the runtime state of the actor. */
  record Status(ActorRef<StatusReply> replyTo) implements ProductCommands {}

  /** Reply to {@link ListProducts}. */
  sealed interface ListReply {
    record Products(List<Product> products) implements ListReply {}
  }

  /** Reply to {@link GetProduct}. */
  sealed interface ProductReply {
    record Found(Product product) implements ProductReply {}

    record Missing(long id) implements ProductReply {}
  }

  /**
   * Reply to the stock commands. Failures travel as data instead of a thrown exception so the actor
   * stays available for the next command.
   */
  sealed interface StockReply {
    record Success(Product product) implements StockReply {}

    record Failure(Exception cause) implements StockReply {}
  }

  /** Reply to {@link Status}. */
  sealed interface StatusReply {
    record Runtime(long stockOperations, String lastFailure) implements StatusReply {}
  }
}
