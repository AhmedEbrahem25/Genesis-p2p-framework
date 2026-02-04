package com.genesis.p2p.application;

/**
 * Interface for node lifecycle management.
 *
 * SOLID Principle: Interface Segregation Principle (ISP)
 * - Separates lifecycle concerns from other node functionality
 * - Clients that only care about lifecycle don't need full Node API
 * - Enables monitoring and orchestration tools to work with lifecycle only
 *
 * Design Pattern: Template Method (partial)
 * - Defines lifecycle operations that implementations must provide
 * - Node class provides concrete implementation with hooks
 *
 * @author Genesis P2P Framework
 * @version 1.0
 */
public interface INodeLifecycle extends AutoCloseable {

    /**
     * Gets the current lifecycle state.
     *
     * @return current state
     */
    Node.State getState();

    /**
     * Checks if the node is in a running state.
     *
     * @return true if running
     */
    boolean isRunning();

    /**
     * Starts the node.
     * Transitions: CREATED → STARTING → RUNNING
     *
     * @throws IllegalStateException if node cannot be started from current state
     */
    void start();

    /**
     * Stops the node gracefully.
     * Transitions: RUNNING → STOPPING → STOPPED
     */
    void stop();

    /**
     * Adds a lifecycle listener.
     *
     * @param listener the listener to add
     */
    void addLifecycleListener(Node.LifecycleListener listener);

    /**
     * Removes a lifecycle listener.
     *
     * @param listener the listener to remove
     */
    void removeLifecycleListener(Node.LifecycleListener listener);

    /**
     * Gets the node's uptime if running.
     *
     * @return uptime duration, or null if not running
     */
    java.time.Duration getUptime();

    /**
     * Gets the failure reason if the node is in FAILED state.
     *
     * @return failure reason, or null if not failed
     */
    String getFailureReason();
}
