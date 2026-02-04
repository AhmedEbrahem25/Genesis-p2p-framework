# 📋 Quick Start Guide - Genesis P2P Framework

**Version:** 2.0  
**Status:** Production Ready  
**Last Updated:** December 19, 2025

---

## ⚡ Quick Start (5 Minutes)

### 1. Prerequisites
```bash
# Required
Java 17+
Maven 3.8+

# Optional (for development)
Git
IDE (IntelliJ IDEA / Eclipse)
```

### 2. Build
```bash
cd genesis-p2p-framework
mvn clean compile
```

### 3. Run Sample Node
```bash
mvn exec:java -Dexec.mainClass="com.genesis.p2p.application.Main" \
  -Dexec.args="start --nodeId=node1 --port=9000"
```

---

## 🎯 Basic Usage

### Create a Node
```java
import com.genesis.p2p.application.Node;
import com.genesis.p2p.application.NodeConfig;

// Load configuration
NodeConfig config = NodeConfig.builder()
    .nodeId("node1")
    .port(9000)
    .bindAddress("0.0.0.0")
    .build();

// Create and start node
Node node = new Node(config);
node.start();
```

### Connect to Network
```java
// Connect to bootstrap peer
node.connectToPeer("bootstrap.example.com", 9000);

// Wait for network initialization
Thread.sleep(5000);

// Check connected peers
System.out.println("Connected peers: " + node.getPeerCount());
```

### Send Message
```java
// Send message to specific peer
String peerId = "peer-id-123";
byte[] message = "Hello P2P!".getBytes();
node.sendMessage(peerId, message);
```

### Receive Messages
```java
// Register message handler
node.onMessage((senderId, message) -> {
    System.out.println("Received from " + senderId + ": " 
                     + new String(message));
});
```

---

## 🔧 Configuration

### Basic Config (JSON)
```json
{
  "nodeId": "node1",
  "port": 9000,
  "bindAddress": "0.0.0.0",
  "transport": {
    "type": "TCP",
    "enableTLS": true,
    "bufferSize": 65536
  },
  "discovery": {
    "enabled": true,
    "bootstrapPeers": [
      "bootstrap1.example.com:9000",
      "bootstrap2.example.com:9000"
    ]
  },
  "security": {
    "enabled": true,
    "trustMode": "REPUTATION",
    "minReputation": 50
  }
}
```

### Load Config
```java
NodeConfig config = ConfigLoader.load("config.json");
Node node = new Node(config);
```

---

## 🔒 Security Setup

### 1. Generate Keys
```java
import com.genesis.p2p.security.keys.KeyPairGenerator;

// Generate ECDSA key pair
KeyPair keyPair = KeyPairGenerator.generateECDSA();

// Save keys
KeyManager.saveKeys(keyPair, "keys/node1");
```

### 2. Enable TLS
```java
config.transport().enableTLS(true);
config.transport().keyStorePath("keys/node1.jks");
config.transport().keyStorePassword("secure-password");
```

### 3. Configure Trust
```java
config.security().trustMode(TrustMode.REPUTATION);
config.security().minReputation(50);
config.security().blacklistEnabled(true);
```

---

## 📊 Monitoring

### Enable Metrics
```java
import com.genesis.p2p.observability.metrics.MetricsRegistry;

MetricsRegistry metrics = node.getMetrics();

// Get statistics
long messagesSent = metrics.getCounter("messages.sent");
long messagesReceived = metrics.getCounter("messages.received");
double avgLatency = metrics.getGauge("latency.average");
```

### Enable Logging
```java
// Configure logback.xml
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/genesis-p2p.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n</pattern>
    </encoder>
  </appender>
  
  <root level="INFO">
    <appender-ref ref="FILE" />
  </root>
</configuration>
```

---

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify -Pintegration-tests
```

### Load Tests
```bash
mvn test -Dtest=LoadTest -DnodeCount=100
```

---

## 🐛 Troubleshooting

### Issue: Port Already in Use
```bash
# Check what's using the port
netstat -ano | findstr :9000

# Change port in config
config.port(9001);
```

### Issue: Connection Timeout
```bash
# Increase timeout
config.transport().connectionTimeout(Duration.ofSeconds(30));

# Check firewall
firewall-cmd --add-port=9000/tcp
```

### Issue: Memory Issues
```bash
# Increase JVM memory
export MAVEN_OPTS="-Xmx2g -Xms1g"
mvn exec:java
```

---

## 📚 Advanced Topics

### Custom Processors
```java
public class MyProcessor implements MessageProcessor {
    @Override
    public void processMessage(Message message) {
        // Your logic here
    }
}

// Register processor
node.getMessageHandler().registerProcessor(
    "MY_MESSAGE_TYPE",
    new MyProcessor(),
    priority,
    async,
    timeout
);
```

### Custom Transport
```java
public class MyTransport extends AbstractTransport {
    @Override
    protected void doStart() throws Exception {
        // Initialize transport
    }
    
    @Override
    protected void doSend(byte[] data, InetSocketAddress dest) {
        // Send implementation
    }
}

// Register transport
TransportFactory.register(TransportType.CUSTOM, MyTransport.class);
```

---

## 🎓 Best Practices

### 1. Resource Management
```java
// Always use try-with-resources
try (Node node = new Node(config)) {
    node.start();
    // Your code
} // Auto-closes resources
```

### 2. Error Handling
```java
node.onError((error) -> {
    log.error("Node error", error);
    // Recovery logic
});
```

### 3. Graceful Shutdown
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutting down...");
    node.stop();
}));
```

---

## 📖 Further Reading

- [FINAL_INTEGRATION_REPORT.md](FINAL_INTEGRATION_REPORT.md) - Complete technical details
- [Architecture Documentation](docs/architecture.md) - System design
- [API Reference](docs/api/) - JavaDoc
- [Security Guide](docs/security.md) - Security best practices

---

## 🆘 Getting Help

### Documentation
1. Check the 16 comprehensive reports
2. Read inline JavaDoc
3. Review example code in `/examples`

### Common Issues
- Build problems: Check Maven version (3.8+)
- Connection issues: Check firewall/ports
- Performance: Enable buffer pooling
- Security: Verify key configuration

### Support Channels
- Issues: GitHub Issues
- Discussions: GitHub Discussions
- Email: support@example.com

---

**Ready to build something amazing with Genesis P2P!** 🚀

**Status:** ✅ Production Ready | **Version:** 2.0 | **Integration:** 99.9%

