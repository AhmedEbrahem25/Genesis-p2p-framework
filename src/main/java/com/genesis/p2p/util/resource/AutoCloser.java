package com.genesis.p2p.util.resource;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoCloser - Automatic resource management with try-with-resources.
 *
 * Implements AutoCloseable for use in try-with-resources statements.
 * Automatically closes all registered resources when leaving scope.
 *
 * Usage:
 * <pre>{@code
 * try (AutoCloser closer = new AutoCloser()) {
 *     InputStream in = closer.add(new FileInputStream("file.txt"));
 *     OutputStream out = closer.add(new FileOutputStream("out.txt"));
 *     // Use resources...
 *     // Resources automatically closed on exit
 * }
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class AutoCloser implements AutoCloseable {

    private static final NodeLogger log = NodeLogger.getLogger(AutoCloser.class);

    private final List<AutoCloseable> resources;
    private final boolean suppressExceptions;

    /**
     * Creates an AutoCloser that propagates exceptions.
     */
    public AutoCloser() {
        this(false);
    }

    /**
     * Creates an AutoCloser with exception handling option.
     *
     * @param suppressExceptions if true, close exceptions are logged but not thrown
     */
    public AutoCloser(boolean suppressExceptions) {
        this.resources = new ArrayList<>();
        this.suppressExceptions = suppressExceptions;
    }

    /**
     * Adds and returns a resource for automatic closing.
     *
     * @param resource the resource to manage
     * @return the same resource (for convenience)
     */
    public <T extends AutoCloseable> T add(T resource) {
        if (resource != null) {
            resources.add(resource);
        }
        return resource;
    }

    /**
     * Adds multiple resources.
     */
    @SafeVarargs
    public final <T extends AutoCloseable> void addAll(T... resources) {
        if (resources != null) {
            for (T resource : resources) {
                add(resource);
            }
        }
    }

    /**
     * Adds resources from iterable.
     */
    public void addAll(Iterable<? extends AutoCloseable> resources) {
        if (resources != null) {
            for (AutoCloseable resource : resources) {
                add(resource);
            }
        }
    }

    /**
     * Closes all resources immediately (before try block exits).
     */
    public void closeNow() throws Exception {
        close();
    }

    /**
     * Closes all resources quietly (no exceptions).
     */
    public void closeQuietly() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception e) {
                log.warn("Error closing resource quietly",
                        "index", i,
                        "error", e.getMessage());
            }
        }
        resources.clear();
    }

    @Override
    public void close() throws Exception {
        Exception firstException = null;

        // Close in reverse order (LIFO)
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            if (resource == null) {
                continue;
            }

            try {
                resource.close();
            } catch (Exception e) {
                if (suppressExceptions) {
                    log.warn("Error closing resource (suppressed)",
                            "index", i,
                            "error", e.getMessage());
                } else {
                    if (firstException == null) {
                        firstException = e;
                    } else {
                        firstException.addSuppressed(e);
                    }
                    log.error("Error closing resource",
                            "index", i,
                            "error", e.getMessage());
                }
            }
        }

        resources.clear();

        // Throw first exception with others suppressed
        if (firstException != null && !suppressExceptions) {
            throw firstException;
        }
    }

    /**
     * Gets number of registered resources.
     */
    public int size() {
        return resources.size();
    }

    /**
     * Checks if any resources are registered.
     */
    public boolean isEmpty() {
        return resources.isEmpty();
    }

    /**
     * Removes all resources without closing them.
     */
    public void clear() {
        resources.clear();
    }

    /**
     * Checks if exceptions will be suppressed.
     */
    public boolean isSuppressingExceptions() {
        return suppressExceptions;
    }

    @Override
    public String toString() {
        return String.format("AutoCloser[resources=%d, suppress=%s]",
                resources.size(), suppressExceptions);
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates an AutoCloser that propagates exceptions.
     */
    public static AutoCloser create() {
        return new AutoCloser();
    }

    /**
     * Creates an AutoCloser that suppresses exceptions.
     */
    public static AutoCloser createQuiet() {
        return new AutoCloser(true);
    }

    /**
     * Creates an AutoCloser with resources already added.
     */
    @SafeVarargs
    public static <T extends AutoCloseable> AutoCloser of(T... resources) {
        AutoCloser closer = new AutoCloser();
        closer.addAll(resources);
        return closer;
    }

    // ==================== Builder Pattern ====================

    /**
     * Builder for fluent construction.
     */
    public static class Builder {
        private final List<AutoCloseable> resources = new ArrayList<>();
        private boolean suppressExceptions = false;

        /**
         * Adds a resource.
         */
        public <T extends AutoCloseable> Builder add(T resource) {
            if (resource != null) {
                resources.add(resource);
            }
            return this;
        }

        /**
         * Sets exception suppression.
         */
        public Builder suppressExceptions(boolean suppress) {
            this.suppressExceptions = suppress;
            return this;
        }

        /**
         * Builds the AutoCloser.
         */
        public AutoCloser build() {
            AutoCloser closer = new AutoCloser(suppressExceptions);
            closer.addAll(resources);
            return closer;
        }
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}

