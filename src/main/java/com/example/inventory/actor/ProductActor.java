package com.example.inventory.actor;

import static com.example.inventory.actor.ProductCommands.*;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.example.inventory.mapper.ProductMapper;
import com.example.inventory.model.Product;
import com.example.inventory.service.InsufficientStockException;
import com.example.inventory.service.InvalidRequestException;
import com.example.inventory.service.ProductNotFoundException;
import java.util.Optional;

/**
 * Owns the stock mutation traffic of the inventory.
 *
 * <p>Reservations and releases are single-threaded here: the actor processes one command at a time
 * before it dispatches the next, so the read-modify-write of {@link ProductMapper} cannot
 * interleave with another stock command. Reads share the same mailbox, which keeps every reply
 * consistent with this ordering.
 *
 * <p>Each message runs on the blocking dispatcher because the SQLite mapper waits on a database
 * lock while another writer holds the write lock; parking those waits on a dedicated pool keeps the
 * actor from starving the Akka default dispatcher.
 */
public final class ProductActor extends AbstractBehavior<ProductCommands> {

  private final ProductMapper mapper;
  private long stockOperations;
  private String lastFailure = "";

  private ProductActor(ActorContext<ProductCommands> context, ProductMapper mapper) {
    super(context);
    this.mapper = mapper;
  }

  public static Behavior<ProductCommands> create(ProductMapper mapper) {
    return Behaviors.setup(context -> new ProductActor(context, mapper));
  }

  @Override
  public Receive<ProductCommands> createReceive() {
    return newReceiveBuilder()
        .onMessage(ListProducts.class, this::onListProducts)
        .onMessage(Status.class, this::onStatus)
        .onMessage(GetProduct.class, this::onGetProduct)
        .onMessage(StockCommand.class, this::onStockCommand)
        .build();
  }

  private Behavior<ProductCommands> onListProducts(ListProducts command) {
    command.replyTo().tell(new ListReply.Products(mapper.findAll()));
    return this;
  }

  private Behavior<ProductCommands> onGetProduct(GetProduct command) {
    Optional<Product> product = mapper.findById(command.id());
    command
        .replyTo()
        .tell(
            product
                .<ProductReply>map(ProductReply.Found::new)
                .orElseGet(() -> new ProductReply.Missing(command.id())));
    return this;
  }

  private Behavior<ProductCommands> onStockCommand(StockCommand command) {
    try {
      apply(command);
      stockOperations++;
      command.replyTo().tell(new StockReply.Success(mapper.findById(command.id()).orElseThrow()));
    } catch (RuntimeException failure) {
      lastFailure = failure.getMessage() == null ? "" : failure.getMessage();
      if (isExpected(failure)) {
        // A rejected reservation is a normal outcome, so it must not flood the log with a stack.
        getContext().getLog().debug("Stock command rejected for product {}", command.id());
      } else {
        getContext().getLog().warn("Stock command failed for product {}", command.id(), failure);
      }
      command.replyTo().tell(new StockReply.Failure(failure));
    }
    return this;
  }

  private static boolean isExpected(RuntimeException failure) {
    return failure instanceof ProductNotFoundException
        || failure instanceof InsufficientStockException
        || failure instanceof InvalidRequestException;
  }

  private void apply(StockCommand command) {
    if (command instanceof ReserveStock) {
      if (mapper.reserveStock(command.id(), command.quantity()) > 0) return;
      if (mapper.findById(command.id()).isEmpty()) throw new ProductNotFoundException(command.id());
      throw new InsufficientStockException(command.id());
    }
    if (mapper.releaseStock(command.id(), command.quantity()) > 0) return;
    if (mapper.findById(command.id()).isEmpty()) throw new ProductNotFoundException(command.id());
    throw new InvalidRequestException("released stock exceeds the supported maximum");
  }

  private Behavior<ProductCommands> onStatus(Status command) {
    command.replyTo().tell(new StatusReply.Runtime(stockOperations, lastFailure));
    return this;
  }
}
