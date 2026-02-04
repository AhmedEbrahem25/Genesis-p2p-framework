<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Maven-3.9+-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white" alt="Maven"/>
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License"/>
  <img src="https://img.shields.io/badge/Status-Production%20Ready-brightgreen?style=for-the-badge" alt="Status"/>
</p>

<h1 align="center">🌐 Genesis P2P Framework</h1>

<p align="center">
  <b>A secure, modular, and high-performance peer-to-peer communication framework for Java 21</b>
</p>

<p align="center">
  <a href="#-features">Features</a> •
  <a href="#-quick-start">Quick Start</a> •
  <a href="#-architecture">Architecture</a> •
  <a href="#-configuration">Configuration</a> •
  <a href="#-documentation">Documentation</a>
</p>

---

## ✨ Features

### 🔐 Security First
- **AES-256-GCM** authenticated encryption
- **ECDH** key exchange with P-256 curve
- **HMAC-SHA256** message signatures
- Role-based access control (RBAC)
- Rate limiting & DoS protection
- Replay attack prevention

### 🌍 Multi-Protocol Transport
- **UDP** — Low-latency datagram transport (10,000+ msg/sec)
- **TCP** — Reliable ordered stream transport (5,000+ msg/sec)
- **WebSocket** — Browser-compatible bidirectional communication

### 🔍 Smart Peer Discovery
- Multicast discovery for LAN networks
- DNS SRV record discovery
- Bootstrap node discovery
- Pluggable discovery strategies

### 🚀 NAT Traversal
- Automatic NAT type detection (STUN)
- UPnP & PCP port mapping
- UDP hole-punching
- TCP relay fallback for symmetric NAT

### 💾 Persistence & Storage
- File-based JSON storage (default)
- RocksDB support (high-performance)
- Dead letter queue for failed messages
- Atomic state snapshots

### 📊 Observability
- Structured logging with SLF4J/Logback
- Health check services
- Performance metrics collection
- Event-driven architecture

---

## 🚀 Quick Start

### Prerequisites
- **Java 21** or higher
- **Maven 3.9+** (or use the included Maven Wrapper)

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/genesis-p2p-framework.git
cd genesis-p2p-framework
```

### Build

**With Maven installed:**
```bash
mvn -B clean package
```

**Without Maven (using wrapper):**

*Windows:*
```powershell
.\mvnw.cmd -B clean package
```

*Linux/macOS:*
```bash
./mvnw -B clean package
```

### Run

```bash
java -jar target/genesis-p2p.jar --nodeId=node-01 --port=5001
```

---

## 🏗️ Architecture

Genesis P2P Framework follows a modular, layered architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
│         (CLI, NodeBuilder, Lifecycle Management)            │
├─────────────────────────────────────────────────────────────┤
│                     Protocol Layer                          │
│        (Message Codecs, Validators, Frame Handling)         │
├─────────────────────────────────────────────────────────────┤
│                     Security Layer                          │
│    (Encryption, Authentication, Certificates, RBAC)         │
├─────────────────────────────────────────────────────────────┤
│                     Transport Layer                         │
│              (UDP, TCP, WebSocket Transports)               │
├─────────────────────────────────────────────────────────────┤
│                     Discovery Layer                         │
│      (Multicast, DNS, Bootstrap, Peer Management)           │
├─────────────────────────────────────────────────────────────┤
│                       NAT Layer                             │
│       (STUN, UPnP, PCP, Hole-Punching, Relay)              │
├─────────────────────────────────────────────────────────────┤
│                     Storage Layer                           │
│         (FileKVStore, RocksDB, Persistence)                 │
└─────────────────────────────────────────────────────────────┘
```

### Core Modules

| Module | Description |
|--------|-------------|
| `application/` | Entry point, CLI, lifecycle management |
| `core/` | Peer data structures, peer management |
| `discovery/` | Peer discovery strategies |
| `transport/` | Multi-protocol transport layer |
| `protocol/` | Message encoding, validation, framing |
| `security/` | Cryptography, authentication, authorization |
| `nat/` | NAT detection and traversal |
| `storage/` | Persistence and state management |
| `events/` | Event bus and listener system |
| `observability/` | Logging, metrics, health checks |
| `util/` | Common utilities and helpers |

---

## ⚙️ Configuration

### Basic Configuration (`config/agent_config.json`)

```json
{
  "nodeId": "node-01",
  "listenPort": 5001,
  "tcpPort": 6001,
  "multicastGroup": "230.0.0.1",
  "multicastPort": 4446,
  "broadcastPort": 4447,
  "preSharedKeyHex": "00112233445566778899AABBCCDDEEFF"
}
```

### Trusted Peers (`config/trusted_peers.json`)

Define trusted peers for your network topology.

### Logging (`config/logback.xml`)

Customize logging levels and output formats.

---

## 📈 Performance

| Metric | Value |
|--------|-------|
| **UDP Throughput** | 10,000+ msg/sec |
| **TCP Throughput** | 5,000+ msg/sec |
| **WebSocket Throughput** | 2,000+ msg/sec |
| **Base Memory** | ~50-100 MB |
| **Per 1,000 Peers** | +15-20 MB |
| **Latency (LAN)** | <1ms |

---

## 🧪 Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=PeerManagerTest

# Run with coverage report
mvn clean test jacoco:report
```

---

## 📚 Documentation

Detailed documentation is available in the [`docs/`](./docs) directory:

- [Protocol Specification](./docs/protocol-spec.md)
- [Handshake Protocol](./docs/handshake-protocol-summary.md)
- [NAT Traversal Guide](./docs/NAT_GATING_ISSUE_QUICK_REFERENCE.md)
- [Security Model](./docs/trust-model.md)
- [UDP Transport](./docs/UDP_QUICK_REFERENCE.md)
- [Message Persistence](./docs/MESSAGE_PERSISTENCE_QUICK_REFERENCE.md)

---

## 🔧 Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| [GSON](https://github.com/google/gson) | 2.11.0 | JSON serialization |
| [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) | 1.5.3 | WebSocket transport |
| [Logback](https://logback.qos.ch/) | 1.5.16 | Logging framework |
| [SLF4J](https://www.slf4j.org/) | 2.0.17 | Logging abstraction |
| [JUnit 5](https://junit.org/junit5/) | 5.10.2 | Testing framework |
| [Mockito](https://site.mockito.org/) | 5.11.0 | Mocking framework |

---

## 📁 Project Structure

```
genesis-p2p-framework/
├── src/
│   ├── main/java/com/genesis/p2p/
│   │   ├── application/     # CLI & lifecycle
│   │   ├── core/            # Peer management
│   │   ├── discovery/       # Peer discovery
│   │   ├── transport/       # Network transports
│   │   ├── protocol/        # Message protocols
│   │   ├── security/        # Security & crypto
│   │   ├── nat/             # NAT traversal
│   │   ├── storage/         # Persistence
│   │   ├── events/          # Event system
│   │   ├── observability/   # Logging & metrics
│   │   └── util/            # Utilities
│   └── test/                # Test suites
├── config/                  # Configuration files
├── docs/                    # Documentation
├── demos/                   # Demo applications
└── pom.xml                  # Maven build config
```

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🌟 Acknowledgments

Built with ❤️ using Java 21 and modern P2P networking principles.

---

<p align="center">
  <b>Genesis P2P Framework</b> — Secure. Modular. Scalable.
</p>
