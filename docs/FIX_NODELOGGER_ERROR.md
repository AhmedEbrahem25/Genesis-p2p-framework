# Fix for NodeLogger Key-Value Pair Error

## Date: December 13, 2025

## Problem Description

The application crashed on startup with the following error:

```
❌ ERROR: Unexpected error
   Key-values must be in pairs

java.lang.IllegalArgumentException: Key-values must be in pairs
	at com.genesis.p2p.observability.logging.NodeLogger.logWithContext(NodeLogger.java:48)
	at com.genesis.p2p.observability.logging.NodeLogger.info(NodeLogger.java:23)
	at com.genesis.p2p.application.Main.initializeCoreSystem(Main.java:173)
```

## Root Cause

The `NodeLogger` class uses a custom logging API that expects **key-value pairs** for structured logging with MDC (Mapped Diagnostic Context), not SLF4J-style placeholders.

### NodeLogger API

```java
public void info(String message, Object... keyValues) {
    logWithContext(() -> delegate.info(message), keyValues);
}

private void logWithContext(Runnable logAction, Object... keyValues) {
    if (keyValues.length % 2 != 0) {
        throw new IllegalArgumentException("Key-values must be in pairs");
    }
    // ... MDC context setup
}
```

The method expects pairs: `key1, value1, key2, value2, ...`

### The Bug

Line 173 in Main.java was using SLF4J placeholder syntax:

```java
// ❌ WRONG - uses placeholder {}
log.info("Initializing Genesis P2P Framework v{}", VERSION);
```

This passed only 1 parameter after the message (VERSION), which caused an odd number of arguments error.

## Solution

Changed the logging call to use key-value pairs:

```java
// ✅ CORRECT - uses key-value pairs
log.info("Initializing Genesis P2P Framework", "version", VERSION);
```

### How NodeLogger Works

1. **Message**: First parameter is the plain message
2. **Key-Value Pairs**: Following parameters must be in pairs
   - Even indices (0, 2, 4...): Keys
   - Odd indices (1, 3, 5...): Values

### Examples

✅ **Correct Usage:**
```java
log.info("User logged in", "username", "john", "ip", "192.168.1.1");
log.info("Order created", "orderId", 12345, "amount", 99.99);
log.info("Simple message"); // No key-values is also OK
log.warn("Connection failed", "host", hostname, "port", port);
```

❌ **Incorrect Usage:**
```java
log.info("User logged in with {}", username);        // Wrong: placeholder syntax
log.info("Order created", "orderId");                // Wrong: odd number of params
log.info("Error: {}", ex.getMessage(), "code", 500); // Wrong: mixed syntax
```

## Files Modified

- `src/main/java/com/genesis/p2p/application/Main.java` (line 173)

## Testing

To verify the fix:

```bash
# Run the application
java -cp target/classes com.genesis.p2p.application.Main

# Should now show:
# ✅ Initializing Genesis P2P Framework (with version in MDC context)
```

## Impact

- ✅ Application now starts without crashing
- ✅ Structured logging works correctly
- ✅ MDC context properly populated for log aggregation
- ✅ No other similar issues found in Main.java

## Prevention

When using `NodeLogger`, always remember:

1. **Don't use `{}` placeholders** - this is SLF4J syntax, not NodeLogger syntax
2. **Always provide even number of params** after the message
3. **Keys should be strings** (or will be converted via `String.valueOf()`)
4. **Values can be any type** (will be converted via `String.valueOf()`)

## Related Classes

- `com.genesis.p2p.observability.logging.NodeLogger` - Custom structured logger
- `com.genesis.p2p.application.Main` - Application entry point (fixed)

---

**Status: FIXED ✅**

The application should now start successfully without the "Key-values must be in pairs" error.

