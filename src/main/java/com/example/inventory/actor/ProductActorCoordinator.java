package com.example.inventory.actor;

import static com.example.inventory.actor.ProductCommands.*;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import com.example.inventory.model.Product;
import com.example.inventory.service.InvalidRequestException;
import com.example.inventory.service.ProductNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Entry point that lets blocking Spring MVC controllers talk to the {@link ProductActor}.
 *
 * <p>Akka only exposes asynchronous replies, so every call here bridges the actor's {@link
 * CompletionStage} answer back to a blocking return value.
 */
@Component
public class ProductActorCoordinator {
  private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

  private final ActorSystem<Void> system;
  private final ActorRef<ProductCommands> actor;

  public ProductActorCoordinator(ActorSystem<Void> system, ActorRef<ProductCommands> actor) {
    this.system = system;
    this.actor = actor;
  }

  public List<Product> list() {
    ListReply reply = ask(ref -> new ListProducts(ref));
    if (reply instanceof ListReply.Products products) return products.products();
    throw unexpected("ListProducts");
  }

  public Product get(long id) {
    ProductReply reply = ask(ref -> new GetProduct(id, ref));
    if (reply instanceof ProductReply.Found found) return found.product();
    if (reply instanceof ProductReply.Missing missing)
      throw new ProductNotFoundException(missing.id());
    throw unexpected("GetProduct");
  }

  public Product reserve(long id, int quantity) {
    validateQuantity(quantity);
    return stockResult(ask(ref -> new ReserveStock(id, quantity, ref)));
  }

  public Product release(long id, int quantity) {
    validateQuantity(quantity);
    return stockResult(ask(ref -> new ReleaseStock(id, quantity, ref)));
  }

  /** Reports how much work the actor has processed; used by the diagnostics endpoint. */
  public StatusReply.Runtime status() {
    StatusReply reply = ask(ref -> new Status(ref));
    if (reply instanceof StatusReply.Runtime runtime) return runtime;
    throw unexpected("Status");
  }

  /**
   * Sends one command and waits for its reply.
   *
   * @param command builds the command once the actor hands it a temporary reply ref
   */
  private <R> R ask(Function<ActorRef<R>, ProductCommands> command) {
    CompletionStage<R> stage =
        AskPattern.ask(
            actor, (ActorRef<R> ref) -> command.apply(ref), ASK_TIMEOUT, system.scheduler());
    try {
      return stage.toCompletableFuture().get(ASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ProductActorUnavailableException(
          "interrupted while waiting for the product actor", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause() == null ? failed : failed.getCause();
      throw new ProductActorUnavailableException("product actor failed to answer", cause);
    } catch (TimeoutException timedOut) {
      throw new ProductActorUnavailableException(
          "product actor did not answer within " + ASK_TIMEOUT, timedOut);
    }
  }

  private static Product stockResult(StockReply reply) {
    if (reply instanceof StockReply.Success success) return success.product();
    if (reply instanceof StockReply.Failure failure) throw asApiException(failure.cause());
    throw unexpected("stock command");
  }

  private static void validateQuantity(int quantity) {
    if (quantity <= 0) throw new InvalidRequestException("quantity must be greater than zero");
  }

  private static RuntimeException asApiException(Throwable cause) {
    if (cause instanceof RuntimeException runtime) return runtime;
    String message = cause.getMessage() == null ? "stock update failed" : cause.getMessage();
    return new InvalidRequestException(message);
  }

  private static ProductActorUnavailableException unexpected(String command) {
    return new ProductActorUnavailableException("unexpected reply to " + command);
  }
}
