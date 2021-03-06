/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.client;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.serializer.ServiceLoaderTypeResolver;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.ConfigurationException;
import io.atomix.catalyst.util.Listener;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.CatalystThreadFactory;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.session.Session;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Provides an interface for submitting {@link Command commands} and {@link Query} queries to the Copycat cluster.
 * <p>
 * Copycat clients are responsible for connecting to the cluster and submitting {@link Command commands} and {@link Query queries}
 * that operate on the cluster's replicated state machine. Copycat clients interact with one or more nodes in a Copycat cluster
 * through a session. When the client is {@link #open() opened}, the client will attempt to one of the known member
 * {@link Address} provided to the builder. As long as the client can communicate with at least one correct member of the
 * cluster, it can open a session. Once the client is able to register a {@link Session}, it will receive an updated list
 * of members for the entire cluster and thereafter be allowed to communicate with all servers.
 * <p>
 * Sessions are created by registering the client through the cluster leader. Clients always connect to a single node in the
 * cluster, and in the event of a node failure or partition, the client will detect the failure and reconnect to a correct server.
 * <p>
 * Clients periodically send <em>keep-alive</em> requests to the server to which they're connected. The keep-alive request
 * interval is determined by the cluster's session timeout, and the session timeout is determined by the leader's configuration
 * at the time that the session is registered. This ensures that clients cannot be misconfigured with a keep-alive interval
 * greater than the cluster's session timeout.
 * <p>
 * Clients communicate with the distributed state machine by submitting {@link Command commands} and {@link Query queries} to
 * the cluster through the {@link #submit(Command)} and {@link #submit(Query)} methods respectively:
 * <pre>
 *   {@code
 *   client.submit(new PutCommand("foo", "Hello world!")).thenAccept(result -> {
 *     System.out.println("Result is " + result);
 *   });
 *   }
 * </pre>
 * All client methods are fully asynchronous and return {@link CompletableFuture}. To block until a method is complete, use
 * the {@link CompletableFuture#get()} or {@link CompletableFuture#join()} methods.
 * <p>
 * <b>Sessions</b>
 * <p>
 * Sessions work to provide linearizable semantics for client {@link Command commands}. When a command is submitted to the cluster,
 * the command will be forwarded to the leader where it will be logged and replicated. Once the command is stored on a majority
 * of servers, the leader will apply it to its state machine and respond according to the command's {@link Command#consistency()}.
 * See the {@link Command.ConsistencyLevel} documentation for more info.
 * <p>
 * Sessions also allow {@link Query queries} (read-only requests) submitted by the client to optionally be executed on follower
 * nodes. When a query is submitted to the cluster, the query's {@link Query#consistency()} will be used to determine how the
 * query is handled. For queries with stronger consistency levels, they will be forwarded to the cluster's leader. For weaker
 * consistency queries, they may be executed on follower nodes according to the consistency level constraints. See the
 * {@link Query.ConsistencyLevel} documentation for more info.
 * <p>
 * Throughout the lifetime of a client, the client may operate on the cluster via multiple sessions according to the configured
 * {@link RecoveryStrategy}. In the event that the client's session expires, the client may reopen a new session and continue
 * to submit operations under the recovered session. The client will always attempt to ensure commands submitted are eventually
 * committed to the cluster even across sessions. If a command is submitted under one session but is not completed before the
 * session is lost and a new session is established, the client will resubmit pending commands from the prior session under
 * the new session. This, though, may break linearizability guarantees since linearizability is only guaranteed within the context
 * if a session.
 * <p>
 * Users should watch the client for {@link io.atomix.copycat.client.CopycatClient.State state} changes to determine when
 * linearizability guarantees may be broken.
 * <p>
 * <pre>
 *   {@code
 *   client.onStateChange(state -> {
 *     if (state == CopycatClient.State.SUSPENDED) {
 *
 *     }
 *   });
 *   }
 * </pre>
 * The most notable client state is the {@link CopycatClient.State#SUSPENDED SUSPENDED} state. This state indicates that
 * the client cannot communicate with the cluster and consistency guarantees <em>may</em> have been broken. While in this
 * state, the client's session from the perspective of servers may timeout, the {@link Session} events sent to the client
 * by the cluster may be lost.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public interface CopycatClient extends Managed<CopycatClient> {

  /**
   * Indicates the state of the client's communication with the Copycat cluster.
   * <p>
   * Throughout the lifetime of a client, the client will transition through various states according to its
   * ability to communicate with the cluster within the context of a {@link Session}. In some cases, client
   * state changes may be indicative of a loss of guarantees. Users of the client should
   * {@link CopycatClient#onStateChange(Consumer) watch the state of the client} to determine when guarantees
   * are lost and react to changes in the client's ability to communicate with the cluster.
   * <p>
   * <pre>
   *   {@code
   *   client.onStateChange(state -> {
   *     switch (state) {
   *       case OPEN:
   *         // The client is healthy
   *         break;
   *       case SUSPENDED:
   *         // The client cannot connect to the cluster and operations may be unsafe
   *         break;
   *       case CLOSED:
   *         // The client has been closed and pending operations have failed
   *         break;
   *     }
   *   });
   *   }
   * </pre>
   * So long as the client is in the {@link #CONNECTED} state, all guarantees with respect to reads and writes will
   * be maintained, and a loss of the {@code CONNECTED} state may indicate a loss of linearizability. See the specific
   * states for more info.
   */
  enum State {

    /**
     * Indicates that the client is connected and its session is open.
     * <p>
     * The {@code CONNECTED} state indicates that the client is healthy and operating normally. {@link Command commands}
     * and {@link Query queries} submitted and completed while the client is in this state are guaranteed to adhere to
     * the respective {@link io.atomix.copycat.client.Command.ConsistencyLevel consistency}
     * {@link io.atomix.copycat.client.Query.ConsistencyLevel levels}.
     */
    CONNECTED,

    /**
     * Indicates that the client is suspended and its session may or may not be expired.
     * <p>
     * The {@code SUSPENDED} state is indicative of an inability to communicate with the cluster within the context of
     * the client's {@link Session}. Operations submitted to or completed by clients in this state should be considered
     * unsafe. An operation submitted to a {@link #CONNECTED} client that transitions to the {@code SUSPENDED} state
     * prior to the operation's completion may be committed multiple times in the event that the underlying session
     * is ultimately {@link Session.State#EXPIRED expired}, thus breaking linearizability. Additionally, state machines
     * may see the session expire while the client is in this state.
     * <p>
     * If the client is configured with a {@link RecoveryStrategy} that recovers the client's session upon expiration,
     * the client will transition back to the {@link #CONNECTED} state once a new session is registered, otherwise the
     * client will transition either to the {@link #CONNECTED} or {@link #CLOSED} state based on whether its session
     * is expired as determined once it re-establishes communication with the cluster.
     * <p>
     * If the client is configured with a {@link RecoveryStrategy} that <em>does not</em> recover the client's session
     * upon a session expiration, all guarantees will be maintained by the client even for operations submitted in this
     * state. If linearizability guarantees are essential, users should use the {@link RecoveryStrategies#CLOSE} strategy
     * and allow the client to fail when its session is lost.
     */
    SUSPENDED,

    /**
     * Indicates that the client is closed.
     * <p>
     * A client may transition to this state as a result of an expired session or an explicit {@link CopycatClient#close() close}
     * by the user. In the event that the client's {@link Session} is lost, if the configured {@link RecoveryStrategy}
     * forces the client to close upon failure, the client will immediately be closed. If the {@link RecoveryStrategy}
     * attempts to recover the client's session, the client still may close if it is unable to register a new session.
     */
    CLOSED

  }

  /**
   * Returns a new Copycat client builder.
   * <p>
   * The provided set of members will be used to connect to the Copycat cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member that can communicate with
   * the cluster's leader.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  static Builder builder(Address... members) {
    return builder(Arrays.asList(Assert.notNull(members, "members")));
  }

  /**
   * Returns a new Copycat client builder.
   * <p>
   * The provided set of members will be used to connect to the Copycat cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member that can communicate with
   * the cluster's leader.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  static Builder builder(Collection<Address> members) {
    return new Builder(members);
  }

  /**
   * Returns the current client state.
   * <p>
   * The client's {@link State} is indicative of the client's ability to communicate with the cluster at any given
   * time. Users of the client should use the state to determine when guarantees may be lost. See the {@link State}
   * documentation for information on the specific states.
   *
   * @return The current client state.
   */
  State state();

  /**
   * Registers a callback to be called when the client's state changes.
   *
   * @param callback The callback to be called when the client's state changes.
   * @return The client state change listener.
   */
  Listener<State> onStateChange(Consumer<State> callback);

  /**
   * Returns the client execution context.
   * <p>
   * The thread context is the event loop that this client uses to communicate with Copycat servers.
   * Implementations must guarantee that all asynchronous {@link CompletableFuture} callbacks are
   * executed on a single thread via the returned {@link ThreadContext}.
   * <p>
   * The {@link ThreadContext} can also be used to access the Copycat client's internal
   * {@link Serializer serializer} via {@link ThreadContext#serializer()}.
   *
   * @return The client thread context.
   */
  ThreadContext context();

  /**
   * Returns the client transport.
   * <p>
   * The transport is the mechanism through which the client communicates with the cluster. The transport cannot
   * be used to access client internals, but it serves only as a mechanism for providing users with the same
   * transport/protocol used by the client.
   *
   * @return The client transport.
   */
  Transport transport();

  /**
   * Returns the client serializer.
   * <p>
   * The serializer can be used to manually register serializable types for submitted {@link Command commands} and
   * {@link Query queries}.
   * <pre>
   *   {@code
   *     client.serializer().register(MyObject.class, 1);
   *     client.serializer().register(MyOtherObject.class, new MyOtherObjectSerializer(), 2);
   *   }
   * </pre>
   *
   * @return The client operation serializer.
   */
  Serializer serializer();

  /**
   * Returns the client session.
   * <p>
   * The {@link Session} object can be used to receive session events from replicated state machines. Session events are
   * named messages. To register a session event handler, use the {@link Session#onEvent(String, Consumer)} method:
   * <pre>
   *   {@code
   *   client.session().onEvent("lock", v -> System.out.println("acquired lock!"));
   *   }
   * </pre>
   * When a server-side state machine {@link Session#publish(String, Object) publishes} an event message to this session, the
   * event message is guaranteed to be received in the order in which it was sent by the state machine. Note that the point
   * in time at which events are received by the client is determined by the {@link Command#consistency()} of the command being
   * executed when the state machine published the event. Events are not necessarily guaranteed to be received by the client
   * during command execution. See the {@link Command.ConsistencyLevel} documentation for more info.
   * <p>
   * The returned {@link Session} instance will remain constant as long as the client maintains its session with the cluster.
   * Maintaining the client's session requires that the client be able to communicate with one server that can communicate
   * with the leader at any given time. During periods where the cluster is electing a new leader, the client's session will
   * not timeout but will resume once a new leader is elected.
   *
   * @return The client session or {@code null} if no session is open.
   */
  Session session();

  /**
   * Submits an operation to the Copycat cluster.
   * <p>
   * This method is provided for convenience. The submitted {@link Operation} must be an instance
   * of {@link Command} or {@link Query}.
   *
   * @param operation The operation to submit.
   * @param <T> The operation result type.
   * @return A completable future to be completed with the operation result.
   * @throws IllegalArgumentException If the {@link Operation} is not an instance of {@link Command} or {@link Query}.
   * @throws NullPointerException if {@code operation} is null
   */
  default <T> CompletableFuture<T> submit(Operation<T> operation) {
    Assert.notNull(operation, "operation");
    if (operation instanceof Command) {
      return submit((Command<T>) operation);
    } else if (operation instanceof Query) {
      return submit((Query<T>) operation);
    } else {
      throw new IllegalArgumentException("unknown operation type");
    }
  }

  /**
   * Submits a command to the Copycat cluster.
   * <p>
   * Commands are used to alter state machine state. All commands will be forwarded to the current cluster leader.
   * Once a leader receives the command, it will write the command to its internal {@code Log} and replicate it to a majority
   * of the cluster. Once the command has been replicated to a majority of the cluster, it will apply the command to its
   * {@code StateMachine} and respond with the result.
   * <p>
   * Once the command has been applied to a server state machine, the returned {@link CompletableFuture}
   * will be completed with the state machine output.
   * <p>
   * Note that all client submissions are guaranteed to be completed in the same order in which they were sent (program order)
   * and on the same thread. This does not, however, mean that they'll be applied to the server-side replicated state machine
   * in that order. State machine order is dependent on the configured {@link Command.ConsistencyLevel}.
   *
   * @param command The command to submit.
   * @param <T> The command result type.
   * @return A completable future to be completed with the command result. The future is guaranteed to be completed after all
   * {@link Command} or {@link Query} submission futures that preceded it. The future will always be completed on the
   * @throws NullPointerException if {@code command} is null
   */
  <T> CompletableFuture<T> submit(Command<T> command);

  /**
   * Submits a query to the Copycat cluster.
   * <p>
   * Queries are used to read state machine state. The behavior of query submissions is primarily dependent on the
   * query's {@link Query.ConsistencyLevel}. For {@link Query.ConsistencyLevel#LINEARIZABLE}
   * and {@link Query.ConsistencyLevel#BOUNDED_LINEARIZABLE} consistency levels, queries will be forwarded
   * to the cluster leader. For lower consistency levels, queries are allowed to read from followers. All queries are executed
   * by applying queries to an internal server state machine.
   * <p>
   * Once the query has been applied to a server state machine, the returned {@link CompletableFuture}
   * will be completed with the state machine output.
   *
   * @param query The query to submit.
   * @param <T> The query result type.
   * @return A completable future to be completed with the query result. The future is guaranteed to be completed after all
   * {@link Command} or {@link Query} submission futures that preceded it. The future will always be completed on the
   * @throws NullPointerException if {@code query} is null
   */
  <T> CompletableFuture<T> submit(Query<T> query);

  /**
   * Registers a void event listener.
   * <p>
   * The registered {@link Runnable} will be {@link Runnable#run() called} when an event is received
   * from the Raft cluster for the client. {@link CopycatService} implementations must guarantee that consumers are
   * always called in the same thread for the session. Therefore, no two events will be received concurrently
   * by the session. Additionally, events are guaranteed to be received in the order in which they were sent by
   * the state machine.
   *
   * @param event The event to which to listen.
   * @param callback The session receive callback.
   * @return The listener context.
   * @throws NullPointerException if {@code event} or {@code callback} is null
   */
  Listener<Void> onEvent(String event, Runnable callback);

  /**
   * Registers an event listener.
   * <p>
   * The registered {@link Consumer} will be {@link Consumer#accept(Object) called} when an event is received
   * from the Raft cluster for the session. {@link CopycatService} implementations must guarantee that consumers are
   * always called in the same thread for the session. Therefore, no two events will be received concurrently
   * by the session. Additionally, events are guaranteed to be received in the order in which they were sent by
   * the state machine.
   *
   * @param event The event to which to listen.
   * @param callback The session receive callback.
   * @param <T> The session event type.
   * @return The listener context.
   * @throws NullPointerException if {@code event} or {@code callback} is null
   */
  <T> Listener<T> onEvent(String event, Consumer<T> callback);

  /**
   * Connects the client to the Copycat cluster.
   * <p>
   * When the client is opened, it will attempt to connect to and register a session with each unique configured server
   * {@link Address}. Once the session is open, the client will transition to the {@link State#CONNECTED} state and the
   * returned {@link CompletableFuture} will be completed.
   * <p>
   * The client will connect to servers in the cluster according to the pattern specified by theconfigured
   * {@link ServerSelectionStrategy}.
   * <p>
   * In the event that the client is unable to register a session through any of the servers listed in the provided
   * {@link Address} list, the client will use the configured {@link ConnectionStrategy} to determine whether and when
   * to retry the registration attempt.
   *
   * @return A completable future to be completed once the client's {@link #session()} is open.
   */
  CompletableFuture<CopycatClient> open();

  /**
   * Recovers the client session.
   * <p>
   * When a client is recovered, the client will create and register a new {@link Session}. Once the session is
   * recovered, the client will transition to the {@link State#CONNECTED} state and resubmit pending operations
   * from the previous session. Pending operations are guaranteed to be submitted to the new session in the same
   * order in which they were submitted to the prior session and prior to submitting any new operations.
   *
   * @return A completable future to be completed once the client's session is recovered.
   */
  CompletableFuture<CopycatClient> recover();

  /**
   * Closes the client.
   * <p>
   * Closing the client will cause the client to unregister its current {@link Session} if open. Once the
   * client's session is unregistered, the returned {@link CompletableFuture} will be completed. If the client
   * is unable to unregister its session, the client will transition to the {@link State#SUSPENDED} state and
   * continue to attempt to reconnect and unregister its session until it is able to unregister its session or
   * determine that it was already expired by the cluster.
   *
   * @return A completable future to be completed once the client has been closed.
   */
  CompletableFuture<Void> close();

  /**
   * Builds a new Copycat client.
   * <p>
   * New client builders should be constructed using the static {@link #builder(Address...)} factory method.
   * <pre>
   *   {@code
   *     CopycatClient client = CopycatClient.builder(new Address("123.456.789.0", 5000), new Address("123.456.789.1", 5000)
   *       .withTransport(new NettyTransport())
   *       .build();
   *   }
   * </pre>
   */
  final class Builder extends io.atomix.catalyst.util.Builder<CopycatClient> {
    private Transport transport;
    private Serializer serializer;
    private CatalystThreadFactory threadFactory;
    private ThreadContext context;
    private Set<Address> members;
    private ConnectionStrategy connectionStrategy = ConnectionStrategies.ONCE;
    private ServerSelectionStrategy serverSelectionStrategy = ServerSelectionStrategies.ANY;
    private RetryStrategy retryStrategy = RetryStrategies.FIBONACCI_BACKOFF;
    private RecoveryStrategy recoveryStrategy = RecoveryStrategies.CLOSE;

    private Builder(Collection<Address> members) {
      this.members = new HashSet<>(Assert.notNull(members, "members"));
    }

    /**
     * Sets the client transport.
     * <p>
     * By default, the client will use the {@code NettyTransport} with an event loop pool equal to
     * {@link Runtime#availableProcessors()}.
     *
     * @param transport The client transport.
     * @return The client builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withTransport(Transport transport) {
      this.transport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the client serializer.
     * <p>
     * By default, the client will use a {@link Serializer} configured with the {@link ServiceLoaderTypeResolver}.
     *
     * @param serializer The client serializer.
     * @return The client builder.
     * @throws NullPointerException if {@code serializer} is null
     */
    public Builder withSerializer(Serializer serializer) {
      this.serializer = Assert.notNull(serializer, "serializer");
      return this;
    }

    /**
     * Sets the client thread factory.
     *
     * @param factory The client thread factory.
     * @return The client builder.
     */
    public Builder withThreadFactory(CatalystThreadFactory factory) {
      this.threadFactory = Assert.notNull(factory, "factory");
      return this;
    }

    /**
     * Sets the client thread context.
     *
     * @param context The client thread context.
     * @return The client builder.
     * @throws NullPointerException if the thread context is {@code null}
     */
    public Builder withThreadContext(ThreadContext context) {
      this.context = Assert.notNull(context, "context");
      return this;
    }

    /**
     * Sets the client connection strategy.
     *
     * @param connectionStrategy The client connection strategy.
     * @return The client builder.
     * @throws NullPointerException If the connection strategy is {@code null}
     */
    public Builder withConnectionStrategy(ConnectionStrategy connectionStrategy) {
      this.connectionStrategy = Assert.notNull(connectionStrategy, "connectionStrategy");
      return this;
    }

    /**
     * Sets the server selection strategy.
     *
     * @param serverSelectionStrategy The server selection strategy.
     * @return The client builder.
     */
    public Builder withServerSelectionStrategy(ServerSelectionStrategy serverSelectionStrategy) {
      this.serverSelectionStrategy = Assert.notNull(serverSelectionStrategy, "serverSelectionStrategy");
      return this;
    }

    /**
     * Sets the operation retry strategy.
     *
     * @param retryStrategy The operation retry strategy.
     * @return The client builder.
     */
    public Builder withRetryStrategy(RetryStrategy retryStrategy) {
      this.retryStrategy = Assert.notNull(retryStrategy, "retryStrategy");
      return this;
    }

    /**
     * Sets the client recovery strategy.
     *
     * @param recoveryStrategy The client recovery strategy.
     * @return The client builder.
     */
    public Builder withRecoveryStrategy(RecoveryStrategy recoveryStrategy) {
      this.recoveryStrategy = Assert.notNull(recoveryStrategy, "recoveryStrategy");
      return this;
    }

    /**
     * @throws ConfigurationException if transport is not configured and {@code io.atomix.catalyst.transport.NettyTransport}
     * is not found on the classpath
     */
    @Override
    public CopycatClient build() {
      // If the transport is not configured, attempt to use the default Netty transport.
      if (transport == null) {
        try {
          transport = (Transport) Class.forName("io.atomix.catalyst.transport.NettyTransport").newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
          throw new ConfigurationException("transport not configured");
        }
      }

      if (threadFactory == null) {
        threadFactory = new CatalystThreadFactory("copycat-client-%d");
      }

      // If a thread context was provided, pass the context to the client.
      if (context != null) {
        context.serializer().resolve(new ServiceLoaderTypeResolver());

        return new DefaultCopycatClient(transport, members, context, threadFactory, serverSelectionStrategy, connectionStrategy, retryStrategy, recoveryStrategy);
      } else {
        // If no serializer instance was provided, create one.
        if (serializer == null) {
          serializer = new Serializer();
        }

        // Add service loader types to the primary serializer.
        serializer.resolve(new ServiceLoaderTypeResolver());

        return new DefaultCopycatClient(transport, members, serializer, threadFactory, serverSelectionStrategy, connectionStrategy, retryStrategy, recoveryStrategy);
      }
    }
  }

}
