# Genesis P2P - Quick Start Guide

## 🚀 Running Your First P2P Network

### Prerequisites
- Java 17+
- Maven 3.6+
- Network connectivity (local or remote)

### Build
```bash
mvn clean package -DskipTests
```

### Run Single Node
```bash
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=node-1 \
  --port=8080
```

### Run Two Nodes (Same Machine)
```bash
# Terminal 1
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=node-1 \
  --port=8080

# Terminal 2  
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=node-2 \
  --port=8090
```

### Run Three Nodes
```bash
# Terminal 1
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=alpha \
  --port=8080

# Terminal 2
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=beta \
  --port=8090

# Terminal 3
java -jar target/genesis-p2p-framework-1.0-SNAPSHOT.jar \
  --node-id=gamma \
  --port=8100
```

---

## 📊 Interactive Commands

Once a node is running, use these commands:

| Command | Description |
|---------|-------------|
| `status` | Show node status and connected peers |
| `health` | Check node health |
| `peers` | List all discovered peers |
| `stats` | Show transport statistics |
| `metrics` | Display metrics |
| `help` | Show all commands |
| `exit` | Shutdown node gracefully |

---

## 🔍 What to Expect

### Startup Sequence (30-60 seconds)
```
1. ✓ Metrics registry
2. ✓ Thread pool manager
3. ✓ Rate limit manager
4. ✓ Retry manager
5. ✓ Persistence facade
6. ✓ Event bus
7. ✓ Security facade
8. ✓ Peer manager
9. ✓ TCP transport
10. ✓ UDP transport
11. ✓ Discovery services
12. ✓ Connection orchestrator
13. 🎉 Node started successfully
```

### Peer Discovery (5-30 seconds)
```
[MulticastDiscovery] Multicast announcement sent
[MulticastDiscovery] Peer discovered via multicast: node-2
[PeerManager] Peer registered: node-2
```

### Connection Establishment (1-5 seconds)
```
[PeerConnectionOrchestrator] Initiating connection to peer: node-2
[TcpTransport] Connected to 192.168.1.100:8091
[HandshakeProcessor] Handshake completed successfully
[PeerConnectionOrchestrator] ✓ Peer connection established successfully
```

### Active Session
```
[Node] Active peers: 2
[TcpTransport] Messages sent: 10, received: 12
```

---

## 📁 File Locations

### Data Directory
```
./data/
├── peers/          # Peer database
├── messages/       # Message store
└── metrics/        # Performance data
```

### Logs
```
./logs/
└── genesis-p2p.log
```

### Configuration
```
./config/
├── agent_config.json
├── logback.xml
├── protocol.json
└── trusted_peers.json
```

---

## 🔧 Configuration Options

### Via Command Line
```bash
--node-id=<id>           # Unique node identifier
--port=<port>            # UDP port (TCP will be port+1)
--profile=<profile>      # dev, prod, test, ha
--daemon                 # Run as daemon
```

### Via Code (NodeBuilder)
```java
Node node = new NodeBuilder()
    .nodeId("my-node")
    .port(8080)
    .enablePersistence()
    .persistenceDirectory("/var/p2p-data")
    .productionProfile()
    .build();
```

---

## 🐛 Troubleshooting

### No Peers Discovered
- Check firewall (UDP 5000, 5001)
- Verify multicast routing
- Check network connectivity
- Look for "Multicast announcement sent" in logs

### Connection Fails
- Check TCP port (default: UDP port + 1)
- Verify firewall rules
- Check logs for "Connection failed" messages
- Ensure nodes are on same network

### High Memory Usage
- Check peer count (`> peers`)
- Review persistence settings
- Monitor message queue depth
- Check for message loops

### Build Errors
```bash
# Clean rebuild
mvn clean install -U

# Skip tests
mvn clean package -DskipTests

# Verbose output
mvn clean compile -X
```

---

## 📊 Monitoring

### Check Status
```bash
> status
```

### Check Health
```bash
> health
```

### View Metrics
```bash
> metrics
```

### Export Metrics
Metrics are available via:
- Log files
- JMX (if enabled)
- Prometheus endpoint (if configured)

---

## 🔐 Security

### Default Behavior
- ✅ Encryption enabled by default
- ✅ Authentication via handshake
- ✅ Message validation
- ✅ Rate limiting

### Disable for Testing
```java
.disableSecurity()  // Not recommended for production
```

---

## 🌐 Network Requirements

### Ports
| Port | Protocol | Purpose |
|------|----------|---------|
| 8080 | UDP | Discovery (default) |
| 8081 | TCP | Messages (default: UDP+1) |
| 5000 | UDP | Multicast group |
| 5001 | UDP | Broadcast |

### Firewall Rules
```bash
# Allow UDP discovery
sudo ufw allow 5000/udp
sudo ufw allow 5001/udp

# Allow per-node ports
sudo ufw allow 8080/udp
sudo ufw allow 8081/tcp
```

---

## 🎯 Performance Tuning

### For Many Peers (100+)
```java
new NodeBuilder()
    .maxPeers(500)
    .minPeers(10)
    .build();
```

### For High Throughput
```java
new NodeBuilder()
    .productionProfile()
    .build();
```

### For Development
```java
new NodeBuilder()
    .developmentProfile()
    .disablePersistence()
    .build();
```

---

## 📚 Further Reading

- **NETWORKING_FLOW_COMPLETE.md** - Complete architecture guide
- **PERSISTENCE_GUIDE.md** - Data persistence configuration
- **AUDIT_VERIFICATION_COMPLETE.md** - Implementation details
- **README.md** - Project overview

---

## 💡 Tips

1. **Start simple**: Run 2 nodes first, then scale
2. **Monitor logs**: Use `tail -f logs/genesis-p2p.log`
3. **Use Wireshark**: Capture traffic for debugging
4. **Check status often**: Use interactive `status` command
5. **Clean shutdown**: Always use `exit` command

---

## ⚡ Common Use Cases

### Local Development
```bash
# Disable persistence for faster iteration
--profile=dev
```

### Production Deployment
```bash
# Enable all features
--profile=prod --daemon
```

### Testing
```bash
# Isolated testing
--profile=test
```

### High Availability
```bash
# Maximum reliability
--profile=ha
```

---

## 🆘 Getting Help

1. Check logs: `./logs/genesis-p2p.log`
2. Use interactive help: `> help`
3. Review documentation in `/docs`
4. Check status: `> status`
5. Verify health: `> health`

---

**Happy P2P Networking! 🌐**

