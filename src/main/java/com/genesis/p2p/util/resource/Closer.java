package com.genesis.p2p.util.resource;

import com.genesis.p2p.observability.logging.NodeLogger;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for safely closing multiple resources.
 *
 * Ensures all resources are closed even if some throw exceptions.
 * Collects all exceptions and reports them.
 *
 * Similar to Google Guava's Closer.
 *
 * Usage:
 * <pre>{@code
 * Closer closer = Closer.create();
 * try {
 *     InputStream in = closer.register(new FileInputStream("file.txt"));
 *     OutputStream out = closer.register(new FileOutputStream("out.txt"));
 *     // Use resources...
 * } catch (Exception e) {
 *     throw closer.rethrow(e);
 * } finally {
 *     closer.close();
 * }
 * }</pre>
 *
 * @author Genesis P2P Framework
 * @version 2.0
 */
public final class Closer implements Closeable {

    private static final NodeLogger log = NodeLogger.getLogger(Closer.class);

    private final List<Closeable> resources;
    private Throwable thrown;

    private Closer() {
        this.resources = new ArrayList<>();
    }

    /**
     * Creates a new Closer.
     */
    public static Closer create() {
        return new Closer();
    }

    /**
     * Registers a closeable resource.
     *
     * @param closeable the resource to register
     * @return the registered resource (for convenience)
     */
    public <C extends Closeable> C register(C closeable) {
        if (closeable != null) {
            resources.add(closeable);
        }
        return closeable;
    }

    /**
     * Registers an AutoCloseable resource.
     */
    public <C extends AutoCloseable> C register(C closeable) {
        if (closeable != null) {
            resources.add(() -> {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return closeable;
    }

    /**
     * Rethrows caught exception, adding suppressed exceptions from close().
     */
    public RuntimeException rethrow(Throwable e) throws RuntimeException {
        if (e == null) {
            throw new NullPointerException("Exception cannot be null");
        }
        thrown = e;
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        throw new RuntimeException(e);
    }

    /**
     * Rethrows caught exception as specified type.
     */
    public <X extends Exception> RuntimeException rethrow(Throwable e, Class<X> declaredType)
            throws X {
        if (e == null) {
            throw new NullPointerException("Exception cannot be null");
        }
        thrown = e;
        if (declaredType.isInstance(e)) {
            throw declaredType.cast(e);
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        throw new RuntimeException(e);
    }

    /**
     * Closes all registered resources in reverse order of registration.
     * If any close() throws, continues closing remaining resources
     * and adds exceptions as suppressed.
     */
    @Override
    public void close() {
        Throwable closeException = null;

        // Close in reverse order (LIFO)
        for (int i = resources.size() - 1; i >= 0; i--) {
            Closeable resource = resources.get(i);
            try {
                resource.close();
            } catch (Throwable e) {
                if (closeException == null) {
                    closeException = e;
                } else {
                    closeException.addSuppressed(e);
                }
                log.warn("Error closing resource", "index", i, "error", e.getMessage());
            }
        }

        // If we have both thrown and close exceptions, combine them
        if (thrown != null && closeException != null) {
            thrown.addSuppressed(closeException);
        } else if (closeException != null) {
            // No thrown exception, but close failed
            if (closeException instanceof RuntimeException) {
                throw (RuntimeException) closeException;
            }
            throw new RuntimeException("Failed to close resources", closeException);
        }
    }

    /**
     * Closes all resources without throwing.
     * Logs errors but doesn't propagate exceptions.
     */
    public void closeQuietly() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            Closeable resource = resources.get(i);
            try {
                resource.close();
            } catch (Throwable e) {
                log.warn("Error closing resource (quiet)", "index", i, "error", e.getMessage());
            }
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
     * Clears all registered resources without closing them.
     */
    public void clear() {
        resources.clear();
        thrown = null;
    }

    @Override
    public String toString() {
        return String.format("Closer[resources=%d]", resources.size());
    }

    // ==================== Static Utility Methods ====================

    /**
     * Closes a single resource quietly (no exceptions thrown).
     */
    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable e) {
                log.debug("Error closing resource quietly", "error", e.getMessage());
            }
        }
    }

    /**
     * Closes a single AutoCloseable quietly.
     */
    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable e) {
                log.debug("Error closing resource quietly", "error", e.getMessage());
            }
        }
    }

    /**
     * Closes multiple resources quietly.
     */
    public static void closeQuietly(Closeable... closeables) {
        if (closeables != null) {
            for (Closeable closeable : closeables) {
                closeQuietly(closeable);
            }
        }
    }

    /**
     * Closes multiple AutoCloseables quietly.
     */
    public static void closeQuietly(AutoCloseable... closeables) {
        if (closeables != null) {
            for (AutoCloseable closeable : closeables) {
                closeQuietly(closeable);
            }
        }
    }

    /**
     * Closes resources from iterable quietly.
     */
    public static void closeQuietly(Iterable<? extends Closeable> closeables) {
        if (closeables != null) {
            for (Closeable closeable : closeables) {
                closeQuietly(closeable);
            }
        }
    }

    /**
     * Closes a resource and wraps checked exception in RuntimeException.
     */
    public static void closeUnchecked(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close resource", e);
            }
        }
    }

    /**
     * Closes an AutoCloseable and wraps checked exception in RuntimeException.
     */
    public static void closeUnchecked(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new RuntimeException("Failed to close resource", e);
            }
        }
    }
}

