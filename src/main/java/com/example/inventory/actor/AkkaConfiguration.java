package com.example.inventory.actor;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import com.example.inventory.mapper.ProductMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Akka actor system into the Spring application context. */
@Configuration
public class AkkaConfiguration {

  /** Akka ships this dispatcher with a dedicated thread pool for actors that block on IO. */
  private static final String BLOCKING_DISPATCHER = "akka.actor.default-blocking-io-dispatcher";

  @Bean(destroyMethod = "terminate")
  public ActorSystem<Void> inventoryActorSystem() {
    return ActorSystem.create(Behaviors.<Void>empty(), "inventory");
  }

  /**
   * Exposes the single product actor to the Spring context.
   *
   * <p>The actor runs on the blocking dispatcher because every command has to reach SQLite, and a
   * restart strategy keeps an unexpected mapper failure from stopping the actor permanently.
   */
  @Bean
  public ActorRef<ProductCommands> productActor(ActorSystem<Void> system, ProductMapper mapper) {
    Behavior<ProductCommands> supervised =
        Behaviors.supervise(ProductActor.create(mapper))
            .onFailure(RuntimeException.class, SupervisorStrategy.restart());
    return system.systemActorOf(
        supervised, "product", DispatcherSelector.fromConfig(BLOCKING_DISPATCHER));
  }
}
