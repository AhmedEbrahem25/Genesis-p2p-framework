# Genesis P2P Framework - CLI Logging Options

## Overview

The Genesis P2P Framework CLI now includes comprehensive logging configuration options that integrate seamlessly with the enhanced logging system implemented across all subsystems.

## Quick Start Examples

### Basic Usage
```bash
# Default INFO-level logging to console
genesis start --nodeId=node1

# Verbose DEBUG logging
genesis start --nodeId=node1 --verbose

# Quiet mode (WARN only)
genesis start --nodeId=node1 --quiet
```

### Production Deployment
```bash
# Production profile with file logging
genesis start --profile=prod --daemon \
  --log-file=/var/log/genesis/node1.log \
  --log-output=both \
  --log-level=INFO

# High-performance mode (minimal logging overhead)
genesis start --profile=prod --performance \
  --log-level=WARN \
  --log-output=file \
  --log-file=/var/log/genesis/node1.log
```

### Development & Debugging
```bash
# Full DEBUG logging with specific categories
genesis start --log-level=DEBUG \
  --log-category=discovery:DEBUG,security:INFO,transport:DEBUG \
  --log-output=both \
  --log-file=debug.log

# JSON formatted logs for structured analysis
genesis start --log-format=json \
  --log-file=structured.log \
  --log-output=both
```

### Network Analysis & Wireshark Correlation
```bash
# Enable Wireshark correlation mode
genesis start --wireshark \
  --log-file=packets.log \
  --max-payload-hex=128 \
  --log-output=file

# Full packet capture with 256-byte hex dumps
genesis start --wireshark \
  --max-payload-hex=256 \
  --log-format=json \
  --log-file=wireshark-correlation.jsonl
```

### Security Testing & Cyber-Emulation
```bash
# Cyber-emulation mode (enhanced security logging)
genesis start --cyber-emulation \
  --log-format=json \
  --log-output=both \
  --log-file=security-audit.jsonl

# Attack pattern detection mode
genesis start --cyber-emulation \
  --log-category=security:DEBUG,nat:DEBUG \
  --wireshark \
  --log-file=attack-analysis.log
```

## Complete Option Reference

### Basic Logging Options

| Option | Description | Default | Example |
|--------|-------------|---------|---------|
| `--log-level=<level>` | Global log level: ERROR, WARN, INFO, DEBUG | INFO | `--log-level=DEBUG` |
| `--verbose` | Shorthand for `--log-level=DEBUG` | - | `--verbose` |
| `--quiet` | Shorthand for `--log-level=WARN` | - | `--quiet` |
| `--log-file=<path>` | Log file path | - | `--log-file=/var/log/genesis.log` |
| `--log-output=<mode>` | Output destination: console, file, both | console | `--log-output=both` |
| `--log-format=<fmt>` | Format: structured, json, plain | structured | `--log-format=json` |
| `--json-log` | Shorthand for `--log-format=json` | - | `--json-log` |

### Advanced Logging Options

| Option | Description | Example |
|--------|-------------|---------|
| `--log-category=<cats>` | Category-specific log levels | `--log-category=discovery:DEBUG,security:INFO` |
| `--wireshark` | Enable Wireshark correlation (payload hex dumps) | `--wireshark` |
| `--cyber-emulation` | Cyber-emulation mode (security verbose) | `--cyber-emulation` |
| `--performance` | Performance mode (disable expensive logging) | `--performance` |
| `--max-payload-hex=<n>` | Max payload hex dump size in bytes | `--max-payload-hex=128` |

## Log Categories

The `--log-category` option allows fine-grained control over logging for specific subsystems:

| Category | Description | Components |
|----------|-------------|------------|
| `discovery` | Peer discovery mechanisms | MulticastDiscovery, BroadcastDiscovery, BootstrapDiscovery |
| `security` | Security operations | SecurityFacade, Encryption, Authentication, Handshakes |
| `transport` | Network transport layers | TcpTransport, UdpTransport, WebSocketTransport |
| `protocol` | Protocol layer operations | ProtocolLayer, Fragmentation, Compression |
| `peer` | Peer management | PeerManager, PeerStore, Connection Orchestrator |
| `nat` | NAT detection & traversal | STUN, NAT types, Port mapping |
| `message` | Message routing & processing | MessageHandler, ProcessorRegistry, Deduplication |
| `resource` | Resource management | Leak detection, Resource tracking |

### Category Usage Examples

```bash
# DEBUG logging for discovery, INFO for everything else
genesis start --log-category=discovery:DEBUG

# Multiple categories with different levels
genesis start --log-category=discovery:DEBUG,security:INFO,transport:WARN

# Debug security and NAT, suppress others
genesis start --log-level=ERROR --log-category=security:DEBUG,nat:DEBUG
```

## Logging Modes

### Wireshark Correlation Mode

Enables detailed packet-level logging for network analysis with Wireshark:

**Features:**
- Payload hex dumps (configurable size)
- Source/destination addresses and ports
- Packet sizes and timing information
- Frame sequence numbers
- Transaction IDs for correlation

**Use Cases:**
- Network troubleshooting
- Protocol debugging
- Packet loss analysis
- Timing analysis

**Example:**
```bash
genesis start --wireshark \
  --max-payload-hex=256 \
  --log-file=packets.log \
  --log-format=json

# Wireshark filter examples from logs:
# udp && ip.src == 192.168.1.100 && ip.dst == 239.255.0.1
# tcp.stream eq 42
# frame.number >= 1000 && frame.number <= 2000
```

### Cyber-Emulation Mode

Enhanced security logging for attack simulation and detection:

**Features:**
- Detailed security event logging
- Authentication failure tracking
- Security violation detection
- Attack pattern identification
- Suspicious activity warnings
- Rate limiting violations

**Use Cases:**
- Penetration testing
- Security audits
- Attack simulation
- Defensive testing
- Compliance logging

**Example:**
```bash
genesis start --cyber-emulation \
  --log-format=json \
  --log-output=both \
  --log-file=security-audit.jsonl \
  --log-category=security:DEBUG,nat:DEBUG
```

### Performance Mode

Optimized for production with minimal logging overhead:

**Features:**
- Disables payload hex dumps
- Disables stack trace logging
- Minimal debug output
- Optimized log formatting

**Use Cases:**
- Production deployments
- High-throughput scenarios
- Resource-constrained environments
- Performance-critical applications

**Example:**
```bash
genesis start --performance \
  --log-level=WARN \
  --log-output=file \
  --log-file=/var/log/genesis/production.log
```

## Log Output Formats

### Structured Format (Default)

Human-readable key-value pairs:

```
2024-12-21 10:30:45 INFO  [Node] NODE_LIFECYCLE_START nodeId=node1 tcpPort=8081 udpPort=8080
2024-12-21 10:30:46 DEBUG [TcpTransport] TCP_CONNECTION_ACCEPT connectionId=abc123 remoteAddr=192.168.1.100 remotePort=45678
```

### JSON Format

Machine-readable structured logs:

```json
{
  "timestamp": "2024-12-21T10:30:45.123Z",
  "level": "INFO",
  "logger": "Node",
  "event": "NODE_LIFECYCLE_START",
  "nodeId": "node1",
  "tcpPort": 8081,
  "udpPort": 8080
}
```

### Plain Format

Simple text format:

```
[2024-12-21 10:30:45] INFO: Node starting (nodeId=node1)
[2024-12-21 10:30:46] DEBUG: TCP connection accepted from 192.168.1.100:45678
```

## Integration with System Properties

All logging options set Java system properties that are used throughout the application:

| CLI Option | System Property | Values |
|------------|----------------|--------|
| `--log-level=DEBUG` | `genesis.log.level` | ERROR, WARN, INFO, DEBUG |
| `--log-format=json` | `genesis.log.format` | structured, json, plain |
| `--log-output=both` | `genesis.log.output` | console, file, both |
| `--log-file=<path>` | `genesis.log.file` | File path |
| `--wireshark` | `genesis.log.wireshark` | true, false |
| `--wireshark` | `genesis.log.payload.hex` | true, false |
| `--cyber-emulation` | `genesis.log.cyber.emulation` | true, false |
| `--cyber-emulation` | `genesis.log.security.verbose` | true, false |
| `--performance` | `genesis.log.performance` | true, false |
| `--max-payload-hex=<n>` | `genesis.log.payload.max` | Integer (bytes) |
| `--log-category=<cat:level>` | `genesis.log.category.<cat>` | ERROR, WARN, INFO, DEBUG |

## Log Events

The enhanced logging system generates structured log events across all subsystems:

### Node Lifecycle
- `NODE_LIFECYCLE_START` - Node starting
- `NODE_LIFECYCLE_STARTED` - Node fully operational
- `NODE_LIFECYCLE_STOP` - Node stopping
- `NODE_LIFECYCLE_STOPPED` - Node fully stopped

### Discovery
- `MULTICAST_DISCOVERY_START` - Multicast discovery starting
- `PEER_DISCOVERED_MULTICAST` - Peer discovered via multicast
- `BROADCAST_DISCOVERY_START` - Broadcast discovery starting
- `PEER_DISCOVERED_BROADCAST` - Peer discovered via broadcast

### Transport
- `TCP_CONNECTION_ACCEPT` - TCP connection accepted
- `TCP_CONNECTION_INITIATE` - TCP connection initiated
- `TCP_HANDSHAKE_COMPLETE` - TCP handshake completed
- `TCP_DATA_SENT` - TCP data sent
- `TCP_DATA_RECEIVED` - TCP data received
- `WEBSOCKET_HANDSHAKE_START` - WebSocket handshake starting
- `WEBSOCKET_FRAME_SENT` - WebSocket frame sent

### Security
- `HANDSHAKE_INITIATE` - Security handshake initiated
- `PUBLIC_KEY_SENT` - Public key sent
- `PUBLIC_KEY_RECEIVED` - Public key received
- `SHARED_SECRET_GENERATED` - Shared secret generated
- `SESSION_KEY_DERIVED` - Session key derived
- `MESSAGE_ENCRYPTED` - Message encrypted
- `MESSAGE_DECRYPTED` - Message decrypted
- `AUTHENTICATION_FAILED` - Authentication failed
- `SECURITY_VIOLATION_DETECTED` - Security violation detected

### Message Routing
- `MESSAGE_RECEIVED` - Message received
- `MESSAGE_DEDUP_CHECK` - Deduplication check
- `MESSAGE_DUPLICATE_DETECTED` - Duplicate message detected
- `MESSAGE_ROUTING` - Message being routed
- `MESSAGE_PROCESSING_START` - Processing started
- `MESSAGE_PROCESSING_COMPLETE` - Processing completed
- `MESSAGE_PROCESSING_FAILED` - Processing failed

## Best Practices

### Development
```bash
# Use DEBUG level with category filtering
genesis start --log-level=DEBUG \
  --log-category=discovery:DEBUG,security:INFO \
  --log-output=both \
  --log-file=dev.log
```

### Testing
```bash
# Use Wireshark mode for network testing
genesis start --wireshark \
  --log-format=json \
  --log-file=test-packets.jsonl \
  --max-payload-hex=128
```

### Production
```bash
# Use performance mode with file logging
genesis start --profile=prod \
  --performance \
  --log-level=WARN \
  --log-output=file \
  --log-file=/var/log/genesis/production.log
```

### Security Auditing
```bash
# Use cyber-emulation mode
genesis start --cyber-emulation \
  --log-format=json \
  --log-file=/var/log/genesis/security-audit.jsonl \
  --log-output=both
```

### Troubleshooting
```bash
# Maximum verbosity with all categories
genesis start --verbose \
  --wireshark \
  --log-output=both \
  --log-file=troubleshoot.log \
  --max-payload-hex=256
```

## Environment Variable Overrides

Logging options can also be set via environment variables:

```bash
export GENESIS_LOG_LEVEL=DEBUG
export GENESIS_LOG_FILE=/var/log/genesis.log
export GENESIS_LOG_FORMAT=json

genesis start --nodeId=node1
```

## Log Rotation

For production deployments, configure log rotation:

```bash
# Example logrotate configuration
/var/log/genesis/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0644 genesis genesis
    sharedscripts
    postrotate
        systemctl reload genesis-p2p || true
    endscript
}
```

## Performance Considerations

| Mode | Overhead | Use Case |
|------|----------|----------|
| `--performance` | Minimal | Production high-throughput |
| `--log-level=INFO` | Low | Production normal |
| `--log-level=DEBUG` | Medium | Development/debugging |
| `--wireshark` | High | Network analysis only |
| `--cyber-emulation` | Medium-High | Security testing only |

## Troubleshooting

### No logs appearing
```bash
# Check log level
genesis start --log-level=DEBUG --verbose

# Check output destination
genesis start --log-output=both --log-file=test.log
```

### Too many logs
```bash
# Use quiet mode or specific categories
genesis start --quiet
genesis start --log-level=ERROR
genesis start --log-category=discovery:ERROR,security:WARN
```

### Missing payload data
```bash
# Enable Wireshark mode
genesis start --wireshark --max-payload-hex=256
```

## Summary

The Genesis P2P Framework CLI logging system provides:

✅ **Comprehensive Configuration** - Fine-grained control over all logging aspects
✅ **Multiple Modes** - Wireshark, cyber-emulation, performance modes
✅ **Category Filtering** - Per-subsystem log level control
✅ **Flexible Output** - Console, file, or both; multiple formats
✅ **Production Ready** - Performance mode for minimal overhead
✅ **Analysis Tools** - Wireshark correlation and security auditing
✅ **Easy Integration** - System properties for programmatic access

For additional help, run:
```bash
genesis help
genesis --help
```
