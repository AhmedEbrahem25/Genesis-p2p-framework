# Genesis P2P Framework - Comprehensive Logging Enhancement Guide

**Version:** 1.0
**Date:** 2025-12-20
**Purpose:** End-to-end observability for packet analysis, cyber-emulation, and production monitoring

---

## Overview

This guide provides specifications for enhancing logging across the entire Genesis P2P Framework to provide comprehensive observability for:
- **Packet-level analysis** (Wireshark correlation)
- **Adversarial/cyber-emulation** use cases
- **Production debugging and monitoring**
- **Security audit trails**
- **Performance profiling**

### Log Levels

- **DEBUG**: Detailed flow control, state transitions, internal operations
- **INFO**: Key events, lifecycle changes, successful operations
- **WARN**: Recoverable errors, degraded performance, suspicious activity
- **ERROR**: Failures, exceptions, security violations

---

## ✅ Phase 1: Node Lifecycle Logging (COMPLETED)

**Files Modified:**
- `src/main/java/com/genesis/p2p/application/Node.java`

**Enhancements Implemented:**
```java
// Startup sequence with timing
NODE_LIFECYCLE_START (nodeId, currentState, targetState, tcpPort, udpPort, protocolVersion, timestamp)
NODE_STATE_TRANSITION (nodeId, fromState, toState, transitionTime)
PERSISTENCE_INIT_START/COMPLETE (nodeId, durationMs)
PROTOCOL_LAYER_START/STARTED (nodeId, protocolVersion, durationMs)
TCP_TRANSPORT_STARTED (nodeId, port, bindAddress, durationMs)
UDP_TRANSPORT_STARTED (nodeId, port, bindAddress, durationMs)
NAT_TRAVERSAL_STARTED (nodeId, durationMs)
DISCOVERY_STARTED (nodeId, service, durationMs)
CONNECTION_ORCHESTRATOR_STARTED (nodeId, durationMs)
NODE_LIFECYCLE_STARTED (nodeId, state, totalStartupTimeMs, tcpPort, udpPort, startedAt)

// Shutdown sequence with phases
NODE_LIFECYCLE_STOP (nodeId, currentState, uptime, timestamp)
CONNECTION_ORCHESTRATOR_STOP/STOPPED (nodeId, phase, durationMs)
DISCOVERY_STOP/STOPPED (nodeId, phase, durationMs)
NAT_TRAVERSAL_STOP/STOPPED (nodeId, phase, durationMs)
TCP_TRANSPORT_STOPPED (nodeId, bytesSent, bytesReceived, durationMs)
UDP_TRANSPORT_STOPPED (nodeId, bytesSent, bytesReceived, durationMs)
PROTOCOL_LAYER_STOP/STOPPED (nodeId, phase, durationMs)
PROCESSORS_SHUTDOWN/SHUTDOWN_COMPLETE (nodeId, phase, durationMs)
MESSAGE_HANDLER_CLOSE/CLOSED (nodeId, phase, durationMs)
PEER_MANAGER_CLOSED (nodeId, activePeersAtShutdown, durationMs)
NODE_LIFECYCLE_STOPPED (nodeId, state, totalShutdownTimeMs, totalUptimeMs, stoppedAt)

// Error handling
NODE_LIFECYCLE_START_FAILED (nodeId, state, failureReason, failureType, timeToFailureMs, stackTrace)
NODE_LIFECYCLE_STOP_FAILED (nodeId, state, failureReason, failureType, timeToFailureMs, stackTrace)
```

**Wireshark Correlation:**
- Timestamp correlation with packet captures
- Port bindings logged for filtering
- Startup/shutdown timing for session analysis

---

## Phase 2: UDP Discovery Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/discovery/multicast/MulticastDiscovery.java`
- `src/main/java/com/genesis/p2p/discovery/broadcast/BroadcastDiscovery.java`
- `src/main/java/com/genesis/p2p/discovery/bootstrap/BootstrapDiscovery.java`

**Required Log Events:**

### Multicast Discovery
```java
// Discovery start
log.info("MULTICAST_DISCOVERY_START",
    "nodeId", nodeId,
    "multicastGroup", multicastGroup,
    "port", port,
    "ttl", ttl);

// Sending discovery request
log.debug("MULTICAST_SEND_REQUEST",
    "nodeId", nodeId,
    "multicastGroup", multicastGroup,
    "port", port,
    "packetSize", packetSize,
    "messageType", "DISCOVERY_REQUEST",
    "sequenceNumber", seq);

// Packet details for Wireshark correlation
log.debug("PACKET_SENT_UDP",
    "protocol", "multicast",
    "srcAddr", localAddress,
    "dstAddr", multicastGroup,
    "srcPort", localPort,
    "dstPort", port,
    "packetSize", packetSize,
    "payloadHex", bytesToHex(payload, 32)); // First 32 bytes

// Receiving discovery response
log.debug("MULTICAST_RECV_RESPONSE",
    "nodeId", nodeId,
    "fromAddr", senderAddress,
    "fromPort", senderPort,
    "packetSize", packetSize,
    "messageType", messageType,
    "peerId", discoveredPeerId);

// Peer discovered
log.info("PEER_DISCOVERED_MULTICAST",
    "nodeId", nodeId,
    "peerId", discoveredPeerId,
    "peerAddr", peerAddress,
    "peerPort", peerPort,
    "responseTime", responseTimeMs);

// Errors
log.warn("MULTICAST_SEND_FAILED",
    "nodeId", nodeId,
    "multicastGroup", multicastGroup,
    "error", error.getMessage(),
    "attemptNumber", attemptNumber);
```

### Broadcast Discovery
```java
// Broadcasting announcement
log.debug("BROADCAST_SEND_ANNOUNCEMENT",
    "nodeId", nodeId,
    "broadcastAddr", broadcastAddress,
    "port", port,
    "packetSize", packetSize,
    "messageType", "PEER_ANNOUNCE");

// Packet details
log.debug("PACKET_SENT_UDP",
    "protocol", "broadcast",
    "srcAddr", localAddress,
    "dstAddr", "255.255.255.255",
    "srcPort", localPort,
    "dstPort", port,
    "packetSize", packetSize,
    "payloadHex", bytesToHex(payload, 32));

// Receiving broadcast
log.debug("BROADCAST_RECV_ANNOUNCEMENT",
    "nodeId", nodeId,
    "fromAddr", senderAddress,
    "fromPort", senderPort,
    "announcedPeerId", peerId,
    "announcedAddr", peerAddress);
```

### Bootstrap Discovery
```java
// Connecting to bootstrap node
log.info("BOOTSTRAP_CONNECT_START",
    "nodeId", nodeId,
    "bootstrapAddr", bootstrapAddress,
    "bootstrapPort", bootstrapPort);

// Bootstrap request
log.debug("BOOTSTRAP_SEND_REQUEST",
    "nodeId", nodeId,
    "bootstrapAddr", bootstrapAddress,
    "requestType", "GET_PEERS");

// Bootstrap response
log.info("BOOTSTRAP_RECV_RESPONSE",
    "nodeId", nodeId,
    "bootstrapAddr", bootstrapAddress,
    "peersReceived", peerList.size(),
    "responseTime", responseTimeMs);

// Each peer received
log.debug("BOOTSTRAP_PEER_RECEIVED",
    "nodeId", nodeId,
    "peerId", peerId,
    "peerAddr", peerAddress,
    "peerPort", peerPort);
```

**Wireshark Filters:**
```
# Multicast discovery
udp.port == 8080 && ip.dst == 239.255.0.1

# Broadcast discovery
udp.port == 8080 && ip.dst == 255.255.255.255

# Correlate with log timestamp
frame.time >= "2025-12-20 10:00:00" && frame.time <= "2025-12-20 10:00:01"
```

---

## Phase 3: NAT Detection & Traversal Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/nat/stun/StunNatDetector.java`
- `src/main/java/com/genesis/p2p/nat/stun/StunClient.java`

**Required Log Events:**

### NAT Type Detection
```java
// STUN detection start
log.info("NAT_DETECTION_START",
    "nodeId", nodeId,
    "stunServers", stunServerList,
    "localAddr", localAddress,
    "localPort", localPort);

// STUN request (for each server)
log.debug("STUN_SEND_REQUEST",
    "nodeId", nodeId,
    "stunServer", stunServer,
    "stunPort", stunPort,
    "transactionId", transactionId,
    "requestType", "BINDING_REQUEST");

// Packet sent
log.debug("PACKET_SENT_UDP",
    "protocol", "stun",
    "srcAddr", localAddress,
    "dstAddr", stunServer,
    "srcPort", localPort,
    "dstPort", stunPort,
    "packetSize", packetSize,
    "payloadHex", bytesToHex(stunPacket, 32));

// STUN response
log.debug("STUN_RECV_RESPONSE",
    "nodeId", nodeId,
    "stunServer", stunServer,
    "transactionId", transactionId,
    "mappedAddr", mappedAddress,
    "mappedPort", mappedPort,
    "sourceAddr", sourceAddress,
    "changedAddr", changedAddress);

// NAT type determined
log.info("NAT_TYPE_DETECTED",
    "nodeId", nodeId,
    "natType", natType, // FULL_CONE, RESTRICTED_CONE, PORT_RESTRICTED, SYMMETRIC
    "publicAddr", publicAddress,
    "publicPort", publicPort,
    "detectionTime", detectionTimeMs);

// NAT mapping
log.info("NAT_MAPPING_CREATED",
    "nodeId", nodeId,
    "internalAddr", internalAddress,
    "internalPort", internalPort,
    "externalAddr", externalAddress,
    "externalPort", externalPort,
    "protocol", "UDP");
```

### STUN Packet Format (for cyber-emulation)
```java
// Detailed STUN packet logging
log.debug("STUN_PACKET_DETAILS",
    "messageType", messageType, // 0x0001 = Binding Request
    "messageLength", messageLength,
    "magicCookie", "0x2112A442",
    "transactionId", transactionId,
    "attributes", attributeList,
    "rawHex", bytesToHex(packet));
```

### NAT Hybrid Traversal

**Purpose:** Log combined/fallback NAT traversal strategies when single techniques fail.

```java
// Hybrid traversal strategy initiated
log.info("NAT_HYBRID_TRAVERSAL_START",
    "nodeId", nodeId,
    "primaryStrategy", primaryStrategy, // STUN, UPNP, PORT_PREDICTION
    "fallbackStrategies", fallbackList, // [UPNP, PORT_PREDICTION]
    "natType", detectedNatType,
    "reason", "primary_strategy_failed");

// Primary strategy attempt
log.debug("NAT_TRAVERSAL_ATTEMPT",
    "nodeId", nodeId,
    "strategy", "STUN",
    "attemptNumber", 1,
    "stunServer", stunServer,
    "timeout", timeoutMs);

// Primary strategy failed
log.warn("NAT_TRAVERSAL_FAILED",
    "nodeId", nodeId,
    "strategy", "STUN",
    "failureReason", failureReason,
    "errorType", errorType, // TIMEOUT, NETWORK_ERROR, INVALID_RESPONSE
    "attemptDuration", durationMs);

// Fallback to secondary strategy
log.info("NAT_TRAVERSAL_FALLBACK",
    "nodeId", nodeId,
    "fromStrategy", "STUN",
    "toStrategy", "UPNP",
    "reason", "stun_timeout",
    "fallbackDelay", delayMs);

// UPnP IGD discovery
log.debug("UPNP_DISCOVERY_START",
    "nodeId", nodeId,
    "multicastAddr", "239.255.255.250",
    "ssdpPort", 1900,
    "deviceType", "InternetGatewayDevice");

// UPnP port mapping request
log.debug("UPNP_PORT_MAPPING_REQUEST",
    "nodeId", nodeId,
    "internalPort", internalPort,
    "externalPort", externalPort,
    "protocol", "UDP",
    "duration", leaseDuration,
    "description", "Genesis-P2P");

// UPnP mapping success
log.info("UPNP_PORT_MAPPING_SUCCESS",
    "nodeId", nodeId,
    "internalAddr", internalAddress,
    "internalPort", internalPort,
    "externalAddr", externalAddress,
    "externalPort", externalPort,
    "leaseDuration", leaseDuration);

// Combined strategy: STUN + Port Prediction
log.info("NAT_COMBINED_STRATEGY",
    "nodeId", nodeId,
    "strategies", ["STUN", "PORT_PREDICTION"],
    "natType", "PORT_RESTRICTED",
    "predictedPorts", predictedPortList);

// Port prediction based on observed pattern
log.debug("NAT_PORT_PREDICTION",
    "nodeId", nodeId,
    "observedMappings", observedMappings, // [(5000->10000), (5001->10001)]
    "predictedPattern", "SEQUENTIAL",
    "nextPredictedPort", nextPort,
    "confidence", confidenceScore);

// Hole punching attempt
log.debug("NAT_HOLE_PUNCH_ATTEMPT",
    "nodeId", nodeId,
    "localAddr", localAddress,
    "localPort", localPort,
    "remoteAddr", remoteAddress,
    "remotePredictedPort", predictedPort,
    "attemptNumber", attemptNumber,
    "technique", "SIMULTANEOUS_OPEN");

// Hole punching success
log.info("NAT_HOLE_PUNCH_SUCCESS",
    "nodeId", nodeId,
    "localEndpoint", localEndpoint,
    "remoteEndpoint", remoteEndpoint,
    "technique", "SIMULTANEOUS_OPEN",
    "attemptsRequired", attempts,
    "totalTime", totalTimeMs);

// Multiple STUN server coordination
log.debug("NAT_MULTI_STUN_COORDINATION",
    "nodeId", nodeId,
    "stunServers", stunServerList,
    "parallelRequests", true,
    "aggregationStrategy", "FIRST_SUCCESS");

// STUN server response comparison
log.debug("NAT_STUN_RESPONSE_COMPARE",
    "nodeId", nodeId,
    "server1", server1,
    "server1MappedPort", port1,
    "server2", server2,
    "server2MappedPort", port2,
    "portConsistent", port1 == port2,
    "natTypeConfidence", confidence);

// Hybrid traversal success
log.info("NAT_HYBRID_TRAVERSAL_SUCCESS",
    "nodeId", nodeId,
    "successfulStrategy", successfulStrategy, // UPNP, STUN+PORT_PREDICTION
    "failedStrategies", failedList,
    "totalAttempts", totalAttempts,
    "totalTime", totalTimeMs,
    "publicAddr", publicAddress,
    "publicPort", publicPort);

// Hybrid traversal complete failure
log.error("NAT_HYBRID_TRAVERSAL_FAILED",
    "nodeId", nodeId,
    "attemptedStrategies", attemptedStrategies,
    "failures", failureMap, // {STUN: "timeout", UPNP: "no_igd", PORT_PREDICTION: "inconsistent"}
    "totalTime", totalTimeMs,
    "fallbackAction", "RELAY_SERVER");

// Relay server fallback
log.warn("NAT_RELAY_FALLBACK",
    "nodeId", nodeId,
    "reason", "all_traversal_failed",
    "relayServer", relayServer,
    "relayPort", relayPort,
    "expectedLatencyIncrease", latencyMs);
```

**Wireshark Correlation for Hybrid Scenarios:**

```
# Track STUN attempts across multiple servers
udp && stun && (ip.dst == 64.233.177.127 || ip.dst == 74.125.250.129)

# Detect UPnP SSDP discovery
udp.port == 1900 && ssdp

# Hole punching simultaneous sends
udp && (ip.src == <local_ip> && ip.dst == <remote_ip>) && frame.time_delta < 0.1

# Port prediction pattern analysis
udp.srcport >= 10000 && udp.srcport <= 10010 && frame.time_relative < 5
```

**Cyber-Emulation Use Cases:**
- **Attack Simulation:** Log NAT traversal attempts from untrusted peers to detect scanning
- **Evasion Detection:** Identify suspicious port prediction patterns (potential firewall bypass)
- **Resource Exhaustion:** Track excessive STUN/UPnP requests from single peer
- **Relay Abuse:** Monitor fallback to relay servers for DDoS amplification attempts

---

## Phase 4: Peer State Transition Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/core/peer/PeerManager.java`
- `src/main/java/com/genesis/p2p/core/peer/PeerStore.java`
- `src/main/java/com/genesis/p2p/core/peer/PeerConnectionOrchestrator.java`

**Required Log Events:**

### State Transitions
```java
// Peer discovered
log.info("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", null,
    "toState", "DISCOVERED",
    "peerAddr", peerAddress,
    "peerPort", peerPort,
    "discoveryMethod", discoveryMethod); // MULTICAST, BROADCAST, BOOTSTRAP

// Connection attempt
log.info("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", "DISCOVERED",
    "toState", "CONNECTING",
    "connectionId", connectionId,
    "attemptNumber", attemptNumber);

// Connection established
log.info("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", "CONNECTING",
    "toState", "CONNECTED",
    "connectionId", connectionId,
    "connectionTime", connectionTimeMs,
    "transport", transport); // TCP, UDP, WEBSOCKET

// Authentication completed
log.info("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", "CONNECTED",
    "toState", "AUTHENTICATED",
    "authMethod", authMethod,
    "authTime", authTimeMs);

// Disconnection
log.info("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", currentState,
    "toState", "DISCONNECTED",
    "reason", disconnectReason,
    "initiator", initiator); // LOCAL, REMOTE

// Peer banned
log.warn("PEER_STATE_TRANSITION",
    "nodeId", nodeId,
    "peerId", peerId,
    "fromState", currentState,
    "toState", "BANNED",
    "reason", banReason,
    "violationType", violationType,
    "banDuration", banDurationMs);
```

### Reputation Changes
```java
log.info("PEER_REPUTATION_CHANGED",
    "nodeId", nodeId,
    "peerId", peerId,
    "oldReputation", oldReputation,
    "newReputation", newReputation,
    "change", change,
    "reason", reason,
    "event", event); // MESSAGE_RECEIVED, TIMEOUT, INVALID_MESSAGE
```

---

## Phase 5: TCP/WebSocket Connection Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/transport/tcp/TcpTransport.java`
- `src/main/java/com/genesis/p2p/transport/tcp/TcpConnection.java`
- `src/main/java/com/genesis/p2p/transport/ws/WebSocketTransport.java`

**Required Log Events:**

### TCP Connection Establishment
```java
// Server accepting connection
log.info("TCP_CONNECTION_ACCEPT",
    "nodeId", nodeId,
    "localAddr", localAddress,
    "localPort", localPort,
    "remoteAddr", remoteAddress,
    "remotePort", remotePort,
    "connectionId", connectionId);

// Client connecting
log.info("TCP_CONNECTION_INITIATE",
    "nodeId", nodeId,
    "peerId", peerId,
    "remoteAddr", remoteAddress,
    "remotePort", remotePort,
    "connectionId", connectionId);

// TCP handshake complete
log.debug("TCP_HANDSHAKE_COMPLETE",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "localAddr", localAddress + ":" + localPort,
    "remoteAddr", remoteAddress + ":" + remotePort,
    "handshakeTime", handshakeTimeMs);

// Data sent
log.debug("TCP_DATA_SENT",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "peerId", peerId,
    "bytesSent", bytesSent,
    "messageType", messageType,
    "sequenceNumber", sequenceNumber);

// Data received
log.debug("TCP_DATA_RECEIVED",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "peerId", peerId,
    "bytesReceived", bytesReceived,
    "messageType", messageType,
    "sequenceNumber", sequenceNumber);

// Connection closed
log.info("TCP_CONNECTION_CLOSED",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "peerId", peerId,
    "closeReason", closeReason,
    "bytesSent", totalBytesSent,
    "bytesReceived", totalBytesReceived,
    "connectionDuration", connectionDurationMs);
```

### WebSocket Connection
```java
// WebSocket handshake
log.info("WEBSOCKET_HANDSHAKE_START",
    "nodeId", nodeId,
    "remoteAddr", remoteAddress,
    "path", requestPath,
    "upgradeHeader", upgradeHeader);

// Handshake complete
log.info("WEBSOCKET_HANDSHAKE_COMPLETE",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "remoteAddr", remoteAddress,
    "protocol", subprotocol,
    "handshakeTime", handshakeTimeMs);

// Frame sent/received
log.debug("WEBSOCKET_FRAME_SENT",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "opcode", opcode, // TEXT, BINARY, PING, PONG, CLOSE
    "frameSize", frameSize,
    "masked", masked);

log.debug("WEBSOCKET_FRAME_RECEIVED",
    "nodeId", nodeId,
    "connectionId", connectionId,
    "opcode", opcode,
    "frameSize", frameSize,
    "masked", masked);
```

---

## Phase 6: Protocol Fragmentation & Compression Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/protocol/ProtocolLayer.java`
- `src/main/java/com/genesis/p2p/protocol/model/Frame.java`

**Required Log Events:**

### Message Fragmentation
```java
// Message fragmentation triggered
log.debug("MESSAGE_FRAGMENTATION_START",
    "nodeId", nodeId,
    "messageId", messageId,
    "messageSize", messageSize,
    "frameSize", frameSize,
    "totalFrames", totalFrames,
    "messageType", messageType);

// Each frame created
log.debug("FRAME_CREATED",
    "nodeId", nodeId,
    "messageId", messageId,
    "frameNumber", frameNumber,
    "totalFrames", totalFrames,
    "frameSize", frameSize,
    "offset", offset,
    "isLast", isLastFrame);

// Frame sent
log.debug("FRAME_SENT",
    "nodeId", nodeId,
    "messageId", messageId,
    "frameNumber", frameNumber,
    "totalFrames", totalFrames,
    "destination", destination,
    "transport", transport);

// Frame received
log.debug("FRAME_RECEIVED",
    "nodeId", nodeId,
    "messageId", messageId,
    "frameNumber", frameNumber,
    "totalFrames", totalFrames,
    "source", source,
    "transport", transport);

// Message reassembly
log.debug("MESSAGE_REASSEMBLY_START",
    "nodeId", nodeId,
    "messageId", messageId,
    "framesReceived", framesReceived,
    "totalFrames", totalFrames);

log.debug("MESSAGE_REASSEMBLY_COMPLETE",
    "nodeId", nodeId,
    "messageId", messageId,
    "totalFrames", totalFrames,
    "totalSize", totalSize,
    "reassemblyTime", reassemblyTimeMs);

// Missing frames
log.warn("FRAME_MISSING",
    "nodeId", nodeId,
    "messageId", messageId,
    "missingFrames", missingFrameNumbers,
    "receivedFrames", receivedFrameNumbers);
```

### Compression
```java
// Compression applied
log.debug("MESSAGE_COMPRESSION_APPLIED",
    "nodeId", nodeId,
    "messageId", messageId,
    "codec", codecName, // GZIP, LZ4
    "originalSize", originalSize,
    "compressedSize", compressedSize,
    "compressionRatio", compressionRatio,
    "compressionTime", compressionTimeMs);

// Compression skipped
log.debug("MESSAGE_COMPRESSION_SKIPPED",
    "nodeId", nodeId,
    "messageId", messageId,
    "reason", reason, // BELOW_THRESHOLD, NOT_BENEFICIAL
    "originalSize", originalSize);

// Decompression
log.debug("MESSAGE_DECOMPRESSION",
    "nodeId", nodeId,
    "messageId", messageId,
    "codec", codecName,
    "compressedSize", compressedSize,
    "decompressedSize", decompressedSize,
    "decompressionTime", decompressionTimeMs);
```

---

## Phase 7: Security Handshake & Validation Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/security/facade/SecurityFacade.java`
- `src/main/java/com/genesis/p2p/protocol/handshake/HandshakeProcessor.java`

**Required Log Events:**

### Key Exchange
```java
// Handshake initiation
log.info("HANDSHAKE_INITIATE",
    "nodeId", nodeId,
    "peerId", peerId,
    "algorithm", algorithm, // ECDH
    "keySize", keySize);

// Public key sent
log.debug("PUBLIC_KEY_SENT",
    "nodeId", nodeId,
    "peerId", peerId,
    "keyFormat", keyFormat,
    "keySize", keySize,
    "keyHash", sha256(publicKey));

// Public key received
log.debug("PUBLIC_KEY_RECEIVED",
    "nodeId", nodeId,
    "peerId", peerId,
    "keyFormat", keyFormat,
    "keySize", keySize,
    "keyHash", sha256(receivedPublicKey));

// Shared secret generated
log.debug("SHARED_SECRET_GENERATED",
    "nodeId", nodeId,
    "peerId", peerId,
    "algorithm", algorithm,
    "secretSize", secretSize,
    "secretHash", sha256(sharedSecret));

// Session key derived
log.info("SESSION_KEY_DERIVED",
    "nodeId", nodeId,
    "peerId", peerId,
    "derivationFunction", derivationFunction, // HKDF
    "keySize", keySize,
    "sessionId", sessionId);
```

### Encryption/Decryption
```java
// Message encryption
log.debug("MESSAGE_ENCRYPTED",
    "nodeId", nodeId,
    "peerId", peerId,
    "messageId", messageId,
    "algorithm", algorithm, // AES-256-GCM
    "plaintextSize", plaintextSize,
    "ciphertextSize", ciphertextSize,
    "nonce", bytesToHex(nonce),
    "authTagSize", authTagSize);

// Message decryption
log.debug("MESSAGE_DECRYPTED",
    "nodeId", nodeId,
    "peerId", peerId,
    "messageId", messageId,
    "algorithm", algorithm,
    "ciphertextSize", ciphertextSize,
    "plaintextSize", plaintextSize,
    "authTagVerified", authTagVerified);

// Authentication failure
log.error("AUTHENTICATION_FAILED",
    "nodeId", nodeId,
    "peerId", peerId,
    "messageId", messageId,
    "reason", reason, // INVALID_TAG, DECRYPTION_FAILED
    "attemptNumber", attemptNumber);
```

### Security Violations
```java
log.warn("SECURITY_VIOLATION_DETECTED",
    "nodeId", nodeId,
    "peerId", peerId,
    "violationType", violationType, // REPLAY_ATTACK, INVALID_SIGNATURE, EXPIRED_CERT
    "messageId", messageId,
    "sequenceNumber", sequenceNumber,
    "timestamp", timestamp,
    "severity", severity); // LOW, MEDIUM, HIGH, CRITICAL
```

---

## Phase 8: Message Routing Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/core/MessageHandler.java`
- `src/main/java/com/genesis/p2p/core/handlers/processors/ProcessorRegistry.java`

**Required Log Events:**

### Message Reception
```java
// Message received
log.debug("MESSAGE_RECEIVED",
    "nodeId", nodeId,
    "messageId", messageId,
    "messageType", messageType,
    "source", sourceAddress,
    "peerId", peerId,
    "messageSize", messageSize,
    "transport", transport,
    "encrypted", encrypted);

// Deduplication check
log.debug("MESSAGE_DEDUP_CHECK",
    "nodeId", nodeId,
    "messageId", messageId,
    "isDuplicate", isDuplicate,
    "cacheSize", dedupCacheSize);

// Duplicate detected
log.warn("MESSAGE_DUPLICATE_DETECTED",
    "nodeId", nodeId,
    "messageId", messageId,
    "messageType", messageType,
    "peerId", peerId,
    "firstSeen", firstSeenTimestamp,
    "duplicate", duplicateTimestamp);

// Routing decision
log.debug("MESSAGE_ROUTING",
    "nodeId", nodeId,
    "messageId", messageId,
    "messageType", messageType,
    "handler", handlerName,
    "priority", priority,
    "async", async);

// Handler processing
log.debug("MESSAGE_PROCESSING_START",
    "nodeId", nodeId,
    "messageId", messageId,
    "handler", handlerName,
    "threadId", Thread.currentThread().getId());

log.debug("MESSAGE_PROCESSING_COMPLETE",
    "nodeId", nodeId,
    "messageId", messageId,
    "handler", handlerName,
    "processingTime", processingTimeMs,
    "result", result);

// Processing error
log.error("MESSAGE_PROCESSING_FAILED",
    "nodeId", nodeId,
    "messageId", messageId,
    "handler", handlerName,
    "error", error.getMessage(),
    "stackTrace", getStackTrace(error));
```

---

## Phase 9: Resource Leak Detection Logging

**Files to Modify:**
- `src/main/java/com/genesis/p2p/util/resource/ResourceLeakDetector.java`
- `src/main/java/com/genesis/p2p/transport/core/AbstractTransport.java`

**Required Log Events:**

### Resource Tracking
```java
// Resource tracked
log.debug("RESOURCE_TRACKED",
    "resourceType", resourceType, // Transport, Connection, Socket
    "resourceId", resourceId,
    "allocationSite", allocationSite,
    "threadId", threadId,
    "timestamp", timestamp);

// Resource released
log.debug("RESOURCE_RELEASED",
    "resourceType", resourceType,
    "resourceId", resourceId,
    "lifetime", lifetimeMs,
    "timestamp", timestamp);

// Leak detected
log.error("RESOURCE_LEAK_DETECTED",
    "resourceType", resourceType,
    "resourceId", resourceId,
    "allocationSite", allocationSite,
    "allocationThread", allocationThread,
    "allocationTime", allocationTime,
    "leakDetectionTime", leakDetectionTime,
    "lifetime", lifetimeMs,
    "stackTrace", allocationStackTrace);

// Leak summary
log.warn("RESOURCE_LEAK_SUMMARY",
    "totalTracked", totalTracked,
    "totalReleased", totalReleased,
    "totalLeaked", totalLeaked,
    "leakRate", leakRate,
    "byType", leaksByType);
```

---

## Logging Best Practices

### 1. Structured Logging Format
Always use key-value pairs:
```java
log.info("EVENT_NAME",
    "field1", value1,
    "field2", value2,
    "field3", value3);
```

### 2. Correlation IDs
Include correlation IDs for tracing:
- `nodeId` - Always include
- `messageId` - For message flow
- `connectionId` - For connection lifetime
- `transactionId` - For request/response pairs

### 3. Packet-Level Details
For Wireshark correlation:
- Source/destination addresses and ports
- Packet sizes
- Payload hex dumps (first 32-64 bytes)
- Sequence numbers
- Timestamps

### 4. Performance Metrics
Include timing for operations:
- `durationMs` - Operation duration
- `startTime` / `endTime` - Absolute timestamps
- `timeout` - Configured timeout values

### 5. Security Context
For security audit:
- Algorithm names
- Key sizes and hashes
- Nonce/IV values
- Authentication tags
- Violation types

### 6. Error Context
For debugging:
- Error messages
- Exception types
- Stack traces (for ERROR level)
- Retry counts
- Failure reasons

---

## Wireshark Correlation Guide

### Timestamp Synchronization
```java
// Use ISO-8601 format for easy correlation
String timestamp = Instant.now().toString(); // 2025-12-20T10:30:45.123Z
```

### Packet Filtering Examples
```
# Match log timestamp to packets
frame.time >= "2025-12-20 10:30:45.000" && frame.time <= "2025-12-20 10:30:46.000"

# Filter by addresses from logs
ip.src == 192.168.1.100 && ip.dst == 192.168.1.200

# Filter by ports from logs
udp.port == 8080 || tcp.port == 8081

# Protocol-specific
stun || dns || http.upgrade.keyword == "websocket"
```

### Payload Hex Matching
```java
// Log first 32 bytes of payload
log.debug("PACKET_SENT",
    "payloadHex", bytesToHex(payload, 0, 32));

// Match in Wireshark
udp.payload[0:32] == aa:bb:cc:dd:...
```

---

## Cyber-Emulation Use Cases

### Attack Pattern Detection
```java
// Rate limiting triggered
log.warn("RATE_LIMIT_EXCEEDED",
    "nodeId", nodeId,
    "peerId", peerId,
    "limit", rateLimit,
    "current", currentRate,
    "timeWindow", timeWindowMs,
    "action", "THROTTLE"); // THROTTLE, BLOCK, BAN

// Suspicious behavior
log.warn("SUSPICIOUS_ACTIVITY",
    "nodeId", nodeId,
    "peerId", peerId,
    "pattern", pattern, // PORT_SCAN, FLOOD, MALFORMED_PACKETS
    "confidence", confidence,
    "eventCount", eventCount);
```

### Adversarial Scenarios
```java
// Simulated attack
log.info("ADVERSARIAL_TEST",
    "scenario", scenarioName,
    "attackType", attackType, // DDoS, MitM, Replay
    "phase", phase, // SETUP, ATTACK, VERIFICATION
    "metrics", metrics);
```

---

## Implementation Priority

1. **Critical (Security & Core Flow):**
   - Phase 7: Security handshake
   - Phase 5: TCP/WebSocket connections
   - Phase 8: Message routing

2. **High (Discovery & NAT):**
   - Phase 2: UDP discovery
   - Phase 3: NAT traversal
   - Phase 4: Peer state transitions

3. **Medium (Protocol & Performance):**
   - Phase 6: Protocol fragmentation/compression
   - Phase 9: Resource leak detection

---

## Log Analysis Tools

### Recommended Stack
- **Elasticsearch + Kibana**: Log aggregation and visualization
- **Wireshark**: Packet capture and analysis
- **Splunk**: Security information and event management (SIEM)
- **Jaeger/Zipkin**: Distributed tracing

### Log Parsing
```bash
# Extract structured logs
grep "NODE_LIFECYCLE" logs/node.log | jq -R 'fromjson?'

# Find security violations
grep "SECURITY_VIOLATION" logs/node.log

# Correlate with Wireshark
tshark -r capture.pcap -T fields -e frame.time -e ip.src -e ip.dst -e udp.port
```

---

## Appendix: Helper Methods

### Hex Encoding
```java
private static String bytesToHex(byte[] bytes) {
    return bytesToHex(bytes, 0, bytes.length);
}

private static String bytesToHex(byte[] bytes, int offset, int length) {
    StringBuilder hex = new StringBuilder();
    int end = Math.min(offset + length, bytes.length);
    for (int i = offset; i < end; i++) {
        hex.append(String.format("%02x", bytes[i]));
        if (i < end - 1) hex.append(":");
    }
    return hex.toString();
}
```

### SHA-256 Hash
```java
private static String sha256(byte[] data) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        return bytesToHex(hash, 0, 8); // First 8 bytes
    } catch (Exception e) {
        return "hash-error";
    }
}
```

### Stack Trace
```java
private static String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    return sw.toString();
}
```

---

**Document Status:** Phase 1 & 10 implemented, Phases 2-9 specified
**Next Steps:** Implement remaining phases based on priority
**Maintenance:** Update as new subsystems are added or logging requirements change
