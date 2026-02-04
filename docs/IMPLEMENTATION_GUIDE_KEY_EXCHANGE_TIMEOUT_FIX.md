# KEY_EXCHANGE Timeout & STUN Fixes

## Issues Identified

Based on the test logs (`after-real-test.txt`), three main issues were identified:

### 1. STUN Request Timeout
**Symptom:** `java.util.concurrent.TimeoutException: null` at `StunNatDetector.performDetection`

**Root Cause:** The `StunClient` was not actually sending UDP packets to external STUN servers. It was calling `messageHandler.handleMessage(request)` which processes the message internally instead of sending real RFC 5389 STUN packets.

**Fix:** Rewrote `StunClient.queryServer()` to:
- Create real UDP sockets
- Build proper RFC 5389 STUN Binding Request packets
- Send to external STUN servers (e.g., `stun.l.google.com:19302`)
- Parse STUN Binding Response with XOR-MAPPED-ADDRESS decoding
- Handle timeouts gracefully

### 2. KEY_EXCHANGE Initiation Timeout
**Symptom:** `java.util.concurrent.TimeoutException: null` at `PeerConnectionOrchestrator.connectDirect:546`

**Root Cause:** TCP connection to the peer times out when trying to pre-establish the connection before sending KEY_EXCHANGE_INIT. This is typically caused by:
- Firewalls blocking TCP port 8081
- Cross-network connectivity issues (peers on different subnets/VLANs)
- NAT traversal requirements

**Fixes Applied:**
1. **Increased timeout** from 5s to 10s for cross-network connections
2. **Improved error logging** to show target address, port, and specific error types
3. **Added connection retry mechanism** - Failed connections automatically retry after 30 seconds
4. **Added periodic cleanup** of stuck CHANNEL_NEGOTIATING peers (30s interval)

### 3. Peer Stuck in CHANNEL_NEGOTIATING State
**Symptom:** `PING_FAILED_PEER_NOT_AUTHENTICATED` - Peer remains in `CHANNEL_NEGOTIATING` state

**Root Cause:** When KEY_EXCHANGE fails, the peer state was not being properly reset, leaving it stuck and preventing reconnection attempts.

**Fix:** Added scheduled cleanup task `cleanupStuckChannelNegotiations()` that:
- Runs every 30 seconds
- Detects peers stuck in `CHANNEL_NEGOTIATING` without active KEY_EXCHANGE
- Transitions them to `DISCONNECTED` so they can retry

## Files Modified

1. **`src/main/java/com/genesis/p2p/nat/stun/StunClient.java`**
   - Added real RFC 5389 STUN protocol implementation
   - Added `buildStunBindingRequest()` method
   - Added `parseStunResponse()` method with XOR-MAPPED-ADDRESS decoding
   - Uses dedicated ExecutorService for async UDP operations

2. **`src/main/java/com/genesis/p2p/core/peer/PeerConnectionOrchestrator.java`**
   - Increased `CONNECTION_TIMEOUT_MS` from 5000 to 10000
   - Added `TCP_CONNECT_TIMEOUT_SECONDS = 10`
   - Enhanced `connectDirect()` with detailed error logging
   - Added `scheduleConnectionRetry()` method for automatic retry after failure
   - Added scheduling for `cleanupStuckChannelNegotiations()` in `start()` method

## Test Files Added

1. **`src/test/java/com/genesis/p2p/security/channel/KeyExchangeTest.java`** (26 tests)
   - KeyExchangeInit creation and validation tests
   - KeyExchangeComplete creation and rejection tests
   - SecureChannelNegotiator initiation and cancellation tests
   - EphemeralKeyPair generation and ECDH derivation tests
   - Concurrent access and thread safety tests
   - Error handling and timeout tests

## Configuration Constants

```java
// PeerConnectionOrchestrator.java
CONNECTION_TIMEOUT_MS = 10000      // Semaphore acquire timeout
TCP_CONNECT_TIMEOUT_SECONDS = 10   // TCP socket connect timeout
RETRY_DELAY_SECONDS = 30           // Delay before retry attempt
STUCK_CLEANUP_INTERVAL = 30        // Seconds between cleanup runs
```

## Firewall Requirements

For P2P connections to work, both endpoints must allow:
- **TCP port 8081** (inbound and outbound) - For KEY_EXCHANGE and secure messaging
- **UDP port 8080** (inbound and outbound) - For discovery and NAT traversal

### Windows Firewall
```powershell
netsh advfirewall firewall add rule name="Genesis P2P TCP" dir=in action=allow protocol=tcp localport=8081
netsh advfirewall firewall add rule name="Genesis P2P UDP" dir=in action=allow protocol=udp localport=8080
```

### Linux (iptables)
```bash
sudo iptables -A INPUT -p tcp --dport 8081 -j ACCEPT
sudo iptables -A INPUT -p udp --dport 8080 -j ACCEPT
```

## Connection Flow (After Fixes)

```
1. [Discovery] Peer discovered via multicast/broadcast
       ↓
2. [State] Peer marked as CHANNEL_NEGOTIATING
       ↓
3. [TCP] Pre-establish TCP connection (10s timeout)
       ↓ (success)                    ↓ (timeout)
4. [KEY_EXCHANGE] Send INIT    →    Schedule retry (30s)
       ↓                              Also: Cleanup task will
5. [Wait] Await COMPLETE              reset stuck states
       ↓
6. [State] CHANNEL_ESTABLISHED
       ↓
7. [HANDSHAKE] Encrypted handshake
       ↓
8. [State] CONNECTED → AUTHENTICATED
```

## Metrics Added

- `orchestrator.connections.retry` - Number of retry attempts
- `orchestrator.channel_negotiating.timeout_cleanup` - Stuck states cleaned up

## Testing Recommendations

1. Ensure both endpoints have TCP 8081 open in firewall
2. Verify network connectivity with `telnet <peer-ip> 8081`
3. Check logs for `TCP connection pre-established successfully`
4. Monitor `security status` CLI command for secure channel state
5. If connection fails, watch for automatic retry after 30 seconds

