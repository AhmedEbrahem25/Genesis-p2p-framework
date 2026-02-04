package com.genesis.p2p.testutil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for managing test resource cleanup.
 * <p>
 * Pattern: Resource Management / Try-with-resources style cleanup
 * <p>
 * Ensures that all test resources (connections, files, threads) are properly
 * cleaned up even if tests fail. Continues cleanup even if individual resource
 * cleanup fails.
 * <p>
 * Usage:
 * <pre>
 * ResourceCleanup cleanup = new ResourceCleanup();
 * Node node = cleanup.register(new Node(...));
 * Transport transport = cleanup.register(createTransport());
 *
 * // In @AfterEach:
 * cleanup.cleanupAll();
 * </pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public class ResourceCleanup {

    private static final Logger log = LoggerFactory.getLogger(ResourceCleanup.class);

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Runnable> cleanupActions = new ArrayList<>();

    /**
     * Registers a resource for automatic cleanup.
     * The resource will be closed when cleanupAll() is called.
     *
     * @param resource the resource to register
     * @param <T> type of resource
     * @return the same resource (for fluent API)
     */
    public <T extends AutoCloseable> T register(T resource) {
        if (resource != null) {
            resources.add(resource);
            log.debug("Registered resource for cleanup: {}", resource.getClass().getSimpleName());
        }
        return resource;
    }

    /**
     * Registers a custom cleanup action (for resources that aren't AutoCloseable).
     *
     * @param cleanupAction the cleanup action to run
     */
    public void registerAction(Runnable cleanupAction) {
        if (cleanupAction != null) {
            cleanupActions.add(cleanupAction);
            log.debug("Registered cleanup action");
        }
    }

    /**
     * Cleans up all registered resources and actions.
     * Continues cleanup even if individual resources fail to close.
     * Logs errors but doesn't throw exceptions.
     */
    public void cleanupAll() {
        log.debug("Starting cleanup of {} resources and {} actions",
            resources.size(), cleanupActions.size());

        int successCount = 0;
        int failureCount = 0;

        // Clean up resources in reverse order (LIFO - last registered, first cleaned)
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            try {
                resource.close();
                successCount++;
                log.debug("Successfully closed resource: {}",
                    resource.getClass().getSimpleName());
            } catch (Exception e) {
                failureCount++;
                log.warn("Failed to close resource: {} - {}",
                    resource.getClass().getSimpleName(), e.getMessage());
            }
        }

        // Execute cleanup actions in reverse order
        for (int i = cleanupActions.size() - 1; i >= 0; i--) {
            Runnable action = cleanupActions.get(i);
            try {
                action.run();
                successCount++;
                log.debug("Successfully executed cleanup action");
            } catch (Exception e) {
                failureCount++;
                log.warn("Failed to execute cleanup action: {}", e.getMessage());
            }
        }

        resources.clear();
        cleanupActions.clear();

        if (failureCount > 0) {
            log.warn("Cleanup completed with {} successes and {} failures",
                successCount, failureCount);
        } else {
            log.debug("Cleanup completed successfully ({} resources)", successCount);
        }
    }

    /**
     * Returns the number of registered resources.
     *
     * @return count of resources awaiting cleanup
     */
    public int size() {
        return resources.size() + cleanupActions.size();
    }

    /**
     * Checks if any resources are registered.
     *
     * @return true if resources are registered
     */
    public boolean hasResources() {
        return !resources.isEmpty() || !cleanupActions.isEmpty();
    }
}
