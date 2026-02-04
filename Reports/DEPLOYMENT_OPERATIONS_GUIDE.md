# 🚀 DEPLOYMENT & OPERATIONS GUIDE
## Genesis P2P Framework - Production Readiness & Operational Procedures

**Date**: December 28, 2025  
**Version**: 2.0.0  
**Audience**: DevOps, Operations, System Administrators

---

## 📋 TABLE OF CONTENTS
1. [System Requirements](#system-requirements)
2. [Pre-Deployment Checklist](#pre-deployment-checklist)
3. [Installation & Build](#installation--build)
4. [Configuration Management](#configuration-management)
5. [Deployment Strategies](#deployment-strategies)
6. [Operational Procedures](#operational-procedures)
7. [Monitoring & Alerting](#monitoring--alerting)
8. [Troubleshooting Guide](#troubleshooting-guide)
9. [Performance Tuning](#performance-tuning)
10. [Disaster Recovery](#disaster-recovery)

---

## 📌 SYSTEM REQUIREMENTS

### Minimum Specifications

| Component | Requirement | Notes |
|-----------|-------------|-------|
| **Java** | OpenJDK 21+ | GraalVM also supported |
| **RAM** | 256 MB | 512 MB recommended |
| **Disk** | 100 MB | Includes logs + persistence |
| **CPU** | 1 core minimum | 4 cores recommended |
| **Network** | 1 Mbps | 100 Mbps+ for production |
| **OS** | Linux/Windows/macOS | Any with Java 21 support |

### Recommended Specifications (Production)

| Component | Specification |
|-----------|---------------|
| **Java** | OpenJDK 21 LTS |
| **RAM** | 2-4 GB |
| **Disk** | 500 MB SSD |
| **CPU** | 4+ cores (2.4+ GHz) |
| **Network** | 1 Gbps dedicated link |
| **OS** | Ubuntu 22.04 LTS or later |

### Disk Space Estimation

```
Per 1,000 Peers:
  ├─ peers.json: ~200 KB (compressed)
  ├─ dlq.json: ~100 KB (variable)
  ├─ Snapshots (daily): ~200 KB each
  ├─ Logs (7 days): ~50-100 MB
  └─ Total: ~50-100 MB

Budget: 100 MB minimum, 500 MB recommended
```

### Network Bandwidth

```
Low-Activity Node:
  ├─ Discovery messages: ~100 KB/day
  ├─ Peer heartbeats: ~50 KB/day
  ├─ Control messages: ~50 KB/day
  └─ Total: ~200 KB/day (~0.02 Mbps)

Active Node (100 connected peers):
  ├─ Message traffic: ~10-100 MB/day (application-dependent)
  ├─ Peer heartbeats: ~500 KB/day
  ├─ Discovery: ~100 KB/day
  └─ Total: 10-100 MB/day (0.1-1 Mbps)
```

---

## ✅ PRE-DEPLOYMENT CHECKLIST

### Infrastructure Verification

- [ ] Java 21+ installed and in PATH
- [ ] Maven 3.9+ installed (or use Maven Wrapper)
- [ ] Required ports available:
  - [ ] Application port (default 9876)
  - [ ] Health check port (default 8080)
  - [ ] No firewall blocking
- [ ] Sufficient disk space (>100 MB free)
- [ ] Time synchronization (NTP daemon running)
- [ ] Network connectivity (DNS resolution works)

### Security Preparation

- [ ] Create dedicated service account (non-root)
- [ ] Generate TLS certificates (optional but recommended)
- [ ] Create private key storage directory (`~/.genesis/keys`)
- [ ] Set permissions: `chmod 700 ~/.genesis/keys`
- [ ] Configure firewall rules:
  ```bash
  # Allow inbound on app port
  sudo ufw allow 9876/udp
  sudo ufw allow 9876/tcp
  
  # Allow health check (internal only)
  sudo ufw allow from 127.0.0.1 to 127.0.0.1 port 8080
  ```

### Configuration Preparation

- [ ] Review `config/agent_config.json`
- [ ] Set appropriate logging level
- [ ] Configure bootstrap peers (if joining existing network)
- [ ] Validate all paths are accessible
- [ ] Test configuration:
  ```bash
  java -jar genesis-p2p.jar --config config/agent_config.json --dry-run
  ```

### Monitoring Setup

- [ ] Install monitoring agent (if applicable)
- [ ] Configure log aggregation endpoint
- [ ] Set up alerting rules
- [ ] Create dashboard for key metrics

---

## 🔨 INSTALLATION & BUILD

### Option 1: Using Maven Wrapper (Recommended)

```bash
# Clone repository
git clone https://github.com/org/genesis-p2p-framework.git
cd genesis-p2p-framework

# Build on Windows
.\mvnw.cmd clean package -DskipTests

# Build on Unix/macOS
./mvnw clean package -DskipTests

# Verify build
ls -lh target/genesis-p2p.jar
```

### Option 2: Using System Maven

```bash
# Ensure Maven is installed
mvn --version

# Build
mvn clean package -DskipTests

# Output location
target/genesis-p2p.jar
```

### Option 3: Using Docker (Recommended for Production)

```dockerfile
# Dockerfile
FROM openjdk:21-slim

WORKDIR /app

# Copy JAR
COPY target/genesis-p2p.jar .

# Copy configuration
COPY config/ config/

# Create data directory
RUN mkdir -p data logs

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD java -jar genesis-p2p.jar health || exit 1

# Default command
CMD ["java", "-Xmx1g", "-jar", "genesis-p2p.jar", \
     "--config", "config/agent_config.json"]
```

Build and run:
```bash
# Build image
docker build -t genesis-p2p:2.0.0 .

# Run container
docker run -d \
  --name genesis-node \
  -p 9876:9876/udp \
  -p 9876:9876/tcp \
  -v /data:/app/data \
  -v /config:/app/config \
  genesis-p2p:2.0.0

# Check logs
docker logs -f genesis-node

# Stop gracefully
docker stop genesis-node
```

### Build Verification

```bash
# Check JAR integrity
jar tf target/genesis-p2p.jar | grep -c ".class"
# Should show 235+ class files

# Verify dependencies
jar tf target/genesis-p2p.jar | grep "MANIFEST.MF"
# Should have Class-Path entry

# Test execution
java -jar target/genesis-p2p.jar --version
# Should output version information
```

---

## ⚙️ CONFIGURATION MANAGEMENT

### Configuration File Hierarchy

```
Priority (highest to lowest):
1. Command-line arguments
2. Environment variables
3. Config files (agent_config.json, protocol.json)
4. Compiled defaults
```

### Minimal Configuration

```json
{
  "node": {
    "port": 9876
  },
  "discovery": {
    "enabled": true
  }
}
```

### Production Configuration Template

```json
{
  "node": {
    "id": "prod-node-001",
    "port": 9876,
    "publicPort": 9876,
    "publicIp": "203.0.113.42",
    "threadPoolSize": 16
  },
  "discovery": {
    "enabled": true,
    "interval": 60,
    "timeout": 15,
    "maxRetries": 3,
    "bootstrapNodes": [
      "seed-1@seed1.example.com:9876",
      "seed-2@seed2.example.com:9876"
    ],
    "strategies": ["BOOTSTRAP", "MULTICAST", "DNS"]
  },
  "transport": {
    "primaryProtocol": "TCP",
    "fallbackProtocol": "UDP",
    "connectionTimeout": 10000,
    "readTimeout": 30000,
    "maxConnections": 500,
    "compression": {
      "enabled": true,
      "codec": "GZIP",
      "threshold": 2048
    }
  },
  "security": {
    "encryption": true,
    "encryptionAlgorithm": "AES_256_GCM",
    "signatureAlgorithm": "HMAC_SHA256",
    "trustMode": "STRICT",
    "rateLimiter": {
      "enabled": true,
      "messagesPerSecond": 1000
    }
  },
  "storage": {
    "type": "ROCKSDB",
    "path": "/data/genesis",
    "autoSave": true,
    "saveInterval": 60,
    "persistence": {
      "enabled": true,
      "peersFile": "/data/genesis/peers.db",
      "dlqFile": "/data/genesis/dlq.db"
    }
  },
  "logging": {
    "level": "INFO",
    "appenders": ["FILE", "ASYNC"],
    "file": {
      "path": "/var/log/genesis/app.log",
      "maxSize": "100MB",
      "maxHistory": 7
    }
  },
  "healthCheck": {
    "enabled": true,
    "interval": 30,
    "port": 8080,
    "checks": {
      "nodeRunning": true,
      "memory": true,
      "threadPool": true,
      "diskSpace": true
    }
  }
}
```

### Environment Variables

```bash
# Node configuration
export GENESIS_NODE_ID="prod-node-001"
export GENESIS_PORT=9876
export GENESIS_PUBLIC_IP="203.0.113.42"

# Security
export GENESIS_ENCRYPTION=true
export GENESIS_TRUST_MODE="STRICT"

# Storage
export GENESIS_STORAGE_TYPE="ROCKSDB"
export GENESIS_STORAGE_PATH="/data/genesis"

# Logging
export GENESIS_LOG_LEVEL="INFO"
export GENESIS_LOG_PATH="/var/log/genesis"

# Java runtime
export JAVA_OPTS="-Xmx2g -XX:+UseG1GC"
```

---

## 🚀 DEPLOYMENT STRATEGIES

### Strategy 1: Single Node (Development)

**Setup Time**: 5 minutes  
**Complexity**: Low  
**Scalability**: Low

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Create data directory
mkdir -p data logs

# 3. Run
java -jar target/genesis-p2p.jar \
  --config config/agent_config.json

# 4. Verify
curl http://localhost:8080/health
```

### Strategy 2: Clustered Deployment (Production)

**Setup Time**: 30 minutes  
**Complexity**: High  
**Scalability**: Excellent

```bash
# Prerequisites
# - Load balancer (HAProxy, Nginx)
# - Shared storage (NFS, S3)
# - Service discovery (Consul, Kubernetes)

# 1. Build Docker image
docker build -t genesis-p2p:2.0.0 .

# 2. Define docker-compose.yml
# (See example below)

# 3. Deploy stack
docker-compose up -d

# 4. Monitor
docker-compose logs -f
```

### Docker Compose Example

```yaml
version: '3.8'

services:
  # Load balancer
  nginx:
    image: nginx:alpine
    ports:
      - "9876:9876"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - node1
      - node2
      - node3

  # Genesis nodes
  node1:
    build: .
    environment:
      GENESIS_NODE_ID: prod-node-001
      GENESIS_PORT: 9876
      GENESIS_STORAGE_PATH: /data/genesis
    volumes:
      - node1-data:/data/genesis
      - ./config:/app/config:ro
    networks:
      - genesis-net

  node2:
    build: .
    environment:
      GENESIS_NODE_ID: prod-node-002
      GENESIS_PORT: 9876
      GENESIS_STORAGE_PATH: /data/genesis
    volumes:
      - node2-data:/data/genesis
      - ./config:/app/config:ro
    networks:
      - genesis-net

  node3:
    build: .
    environment:
      GENESIS_NODE_ID: prod-node-003
      GENESIS_PORT: 9876
      GENESIS_STORAGE_PATH: /data/genesis
    volumes:
      - node3-data:/data/genesis
      - ./config:/app/config:ro
    networks:
      - genesis-net

volumes:
  node1-data:
  node2-data:
  node3-data:

networks:
  genesis-net:
    driver: bridge
```

### Strategy 3: Kubernetes Deployment

**Setup Time**: 1 hour  
**Complexity**: Very High  
**Scalability**: Excellent

```yaml
# genesis-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: genesis-p2p
  labels:
    app: genesis-p2p
spec:
  replicas: 3
  selector:
    matchLabels:
      app: genesis-p2p
  template:
    metadata:
      labels:
        app: genesis-p2p
    spec:
      serviceAccountName: genesis
      containers:
      - name: genesis
        image: genesis-p2p:2.0.0
        ports:
        - containerPort: 9876
          protocol: UDP
        - containerPort: 9876
          protocol: TCP
        - containerPort: 8080
          name: health
        env:
        - name: GENESIS_PORT
          value: "9876"
        - name: GENESIS_STORAGE_PATH
          value: /data/genesis
        - name: JAVA_OPTS
          value: "-Xmx2g -XX:+UseG1GC"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        volumeMounts:
        - name: data
          mountPath: /data/genesis
        - name: config
          mountPath: /app/config
          readOnly: true
      volumes:
      - name: data
        persistentVolumeClaim:
          claimName: genesis-pvc
      - name: config
        configMap:
          name: genesis-config

---
apiVersion: v1
kind: Service
metadata:
  name: genesis-p2p
spec:
  type: LoadBalancer
  ports:
  - port: 9876
    targetPort: 9876
    protocol: UDP
    name: udp
  - port: 9876
    targetPort: 9876
    protocol: TCP
    name: tcp
  selector:
    app: genesis-p2p

---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: genesis-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
```

Deploy to Kubernetes:
```bash
# Create namespace
kubectl create namespace genesis

# Deploy
kubectl apply -f genesis-deployment.yaml -n genesis

# Monitor
kubectl logs -f deployment/genesis-p2p -n genesis

# Scale
kubectl scale deployment genesis-p2p --replicas=5 -n genesis
```

---

## 🔧 OPERATIONAL PROCEDURES

### Starting a Node

```bash
# Interactive mode (foreground)
java -jar genesis-p2p.jar

# Daemon mode (background)
nohup java -jar genesis-p2p.jar > logs/genesis.log 2>&1 &
echo $! > genesis.pid

# With custom configuration
java -jar genesis-p2p.jar --config config/production.json

# With JVM tuning
java -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+PrintGCDetails \
     -jar genesis-p2p.jar
```

### Graceful Shutdown

```bash
# Via CLI command
genesis shutdown

# Via signal (recommended)
kill -TERM $(cat genesis.pid)

# Via Docker
docker stop -t 30 genesis-node

# Automated cleanup:
# 1. Stops accepting new connections (10s)
# 2. Drains message queue
# 3. Persists all state
# 4. Closes database connections
# 5. Releases all resources
# 6. Exits with code 0
```

### Monitoring Commands

```bash
# Health check
curl -s http://localhost:8080/health | jq .

# Node metrics
curl -s http://localhost:8080/metrics | jq .

# View logs
tail -f logs/app.log

# Monitor system resources
watch -n 1 'ps aux | grep genesis'

# Check network activity
netstat -tunap | grep 9876
```

### Peer Management

```bash
# List connected peers
genesis peers list

# Get peer details
genesis peers info <peer-id>

# Disconnect from peer
genesis peers disconnect <peer-id>

# Ban peer (add to blocklist)
genesis peers ban <peer-id>

# Trust peer
genesis peers trust <peer-id>
```

### Configuration Updates

```bash
# Validate new configuration
java -jar genesis-p2p.jar --config new-config.json --validate

# Update running config (if hot-reload enabled)
genesis config reload config/new-config.json

# Or restart required:
kill -TERM $(cat genesis.pid)
# Wait for graceful shutdown
java -jar genesis-p2p.jar --config config/new-config.json
```

---

## 📊 MONITORING & ALERTING

### Key Metrics to Monitor

```
Real-time Metrics:
  ├─ messages_sent_total (counter)
  ├─ messages_received_total (counter)
  ├─ messages_failed_total (counter)
  ├─ latency_p95_ms (gauge)
  ├─ connected_peers (gauge)
  ├─ heap_used_bytes (gauge)
  └─ thread_count (gauge)

Aggregated Metrics (per minute):
  ├─ messages_per_second
  ├─ error_rate (%)
  ├─ avg_latency_ms
  ├─ peer_churn (joins/leaves per minute)
  └─ network_utilization (bytes/sec)
```

### Prometheus Configuration

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'genesis'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
    scrape_interval: 10s
```

### Alert Rules

```yaml
# alerts.yml
groups:
  - name: genesis
    rules:
    # High error rate
    - alert: HighErrorRate
      expr: rate(genesis_messages_failed_total[5m]) > 0.05
      for: 5m
      annotations:
        summary: "High message error rate detected"
        
    # Low peer count
    - alert: LowPeerCount
      expr: genesis_connected_peers < 3
      for: 10m
      annotations:
        summary: "Connected peer count below threshold"
        
    # High latency
    - alert: HighLatency
      expr: genesis_latency_p95_ms > 1000
      for: 5m
      annotations:
        summary: "Message latency p95 exceeds 1s"
        
    # Memory pressure
    - alert: HighMemoryUsage
      expr: genesis_heap_used_bytes / genesis_heap_max_bytes > 0.9
      for: 5m
      annotations:
        summary: "Heap memory usage above 90%"
```

### Grafana Dashboard

Key panels to display:
```
1. Messages Throughput (messages/sec)
2. Error Rate (%)
3. Latency Distribution (p50, p95, p99)
4. Connected Peers Count
5. Memory Usage (heap %)
6. CPU Utilization (%)
7. Network I/O (bytes/sec)
8. JVM GC Pause Time
9. Thread Count
10. Disk Usage
```

---

## 🔍 TROUBLESHOOTING GUIDE

### Issue: Node Won't Start

**Symptoms**: Application exits immediately with error

**Diagnostics**:
```bash
# Check Java version
java -version  # Must be 21+

# Check if port is in use
netstat -tunap | grep 9876
lsof -i :9876

# Check configuration validity
java -jar genesis-p2p.jar --validate-config

# Check file permissions
ls -la data/ logs/ config/
```

**Solutions**:
```bash
# Port already in use
kill -9 <PID>  # Kill previous process
# Or use different port:
java -jar genesis-p2p.jar --port 9877

# Configuration error
# Review error message
# Validate JSON: python -m json.tool config/agent_config.json

# Permissions issue
chmod -R 755 data/ logs/
chown -R $(whoami) data/ logs/
```

### Issue: Connection Failures

**Symptoms**: Can't connect to peers, "No route to host"

**Diagnostics**:
```bash
# Check network connectivity
ping <peer-ip>
telnet <peer-ip> <peer-port>

# Check firewall
sudo iptables -L | grep 9876
sudo ufw status

# Check NAT detection
# (Check node logs for NAT type)
```

**Solutions**:
```bash
# Open firewall
sudo ufw allow 9876/udp
sudo ufw allow 9876/tcp

# Check NAT type
# Configure bootstrap nodes:
genesis config set bootstrapNodes="seed@example.com:9876"

# Use TCP instead of UDP (more reliable)
genesis config set primaryProtocol="TCP"
```

### Issue: High Latency

**Symptoms**: Message latency p95 > 1 second

**Diagnostics**:
```bash
# Check CPU usage
top -p <pid>

# Check memory pressure
free -h
ps aux | grep genesis | grep -v grep

# Check network saturation
iftop -i eth0

# Check GC pauses
# (Examine logs for GC messages)
```

**Solutions**:
```bash
# Increase heap memory
export JAVA_OPTS="-Xmx4g -XX:+UseG1GC"
java $JAVA_OPTS -jar genesis-p2p.jar

# Reduce message rate
genesis config set rateLimiter.messagesPerSecond=500

# Enable compression for large messages
genesis config set compression.enabled=true

# Switch to TCP (more reliable than UDP)
genesis config set primaryProtocol="TCP"
```

### Issue: Memory Leak

**Symptoms**: Heap memory continuously increases

**Diagnostics**:
```bash
# Monitor heap over time
jstat -gc <pid> 1000  # Collect every second

# Generate heap dump
jmap -dump:live,format=b,file=heap.bin <pid>

# Analyze with Eclipse MAT
# Download: https://eclipse.dev/mat/
# File > Open > heap.bin
```

**Solutions**:
```bash
# Restart node (gracefully)
kill -TERM $(cat genesis.pid)

# Increase heap size temporarily
export JAVA_OPTS="-Xmx4g"

# Check for message queue buildup
curl -s http://localhost:8080/metrics | jq '.queue_depth'

# Reduce connection pool size
genesis config set maxConnections=100
```

### Issue: Peer Discovery Not Working

**Symptoms**: No peers discovered, empty peer list

**Diagnostics**:
```bash
# Check if discovery is enabled
curl -s http://localhost:8080/metrics | jq '.discovery_enabled'

# Check bootstrap nodes accessibility
ping seed.example.com
nslookup seed.example.com

# Check logs for discovery errors
grep "WARN.*Discovery" logs/app.log
```

**Solutions**:
```bash
# Enable discovery (if disabled)
genesis config set discovery.enabled=true

# Add bootstrap nodes
genesis config set bootstrapNodes="seed1@example.com:9876,seed2@example.com:9876"

# Increase discovery interval
genesis config set discovery.interval=30  # seconds

# Check multicast (if using multicast discovery)
ip maddr show
```

---

## ⚡ PERFORMANCE TUNING

### JVM Tuning

```bash
# G1GC (General purpose, recommended)
java -Xmx4g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:InitiatingHeapOccupancyPercent=35 \
     -jar genesis-p2p.jar

# ZGC (Ultra-low latency, Java 21+)
java -Xmx4g \
     -XX:+UseZGC \
     -XX:ConcGCThreads=2 \
     -jar genesis-p2p.jar

# Profiling
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+DebugNonSafepoints \
     -XX:+TraceClassLoading \
     -jar genesis-p2p.jar
```

### Connection Pool Tuning

```json
{
  "transport": {
    "maxConnections": 500,
    "connectionTimeout": 10000,
    "idleTimeout": 300000
  }
}
```

**Calculation**:
```
Max Connections = Peer Count × 2 + 50

Example:
- 100 peers → 250 max connections
- 500 peers → 1050 max connections
- 1000 peers → 2050 max connections
```

### Message Queue Tuning

```json
{
  "queue": {
    "maxDepth": 10000,
    "rejectionPolicy": "DISCARD_OLDEST",
    "batchSize": 100
  }
}
```

### Compression Tuning

```
Message Size    Recommendation
─────────────────────────────────
< 512 bytes     No compression
512B - 4KB      Conditional (check CPU)
> 4KB           Always compress

Codec Selection:
  - GZIP: Better ratio, slower (use for large, infrequent)
  - LZ4: Faster, lower ratio (use for frequent messages)
```

---

## 🛡️ DISASTER RECOVERY

### Backup Strategy

```bash
# Daily automated backup
0 2 * * * tar -czf /backups/genesis-$(date +\%Y\%m\%d).tar.gz \
  /data/genesis /var/log/genesis

# Backup retention
find /backups -name "genesis-*.tar.gz" -mtime +30 -delete
```

### Recovery Procedure

```bash
# 1. Stop the node
kill -TERM $(cat genesis.pid)

# 2. Restore from backup
cd /backup
tar -xzf genesis-20251228.tar.gz -C /

# 3. Verify data integrity
java -jar genesis-p2p.jar --validate-data

# 4. Start node
java -jar genesis-p2p.jar
```

### Failover Procedure

```bash
# Automated failover (Docker/Kubernetes):
# 1. Container health check fails
# 2. Orchestrator detects unhealthy node
# 3. Spawns replacement instance
# 4. New instance loads persistent state
# 5. Rejoins network automatically

# Manual failover:
# 1. Promote standby node (if exists)
# 2. Update DNS/load balancer
# 3. Verify connectivity to new node
# 4. Investigate failed node

DNS Update:
  genesis.example.com → failover-node-02.example.com
  (Wait for TTL expiration)
```

---

## 📋 APPENDIX

### Quick Command Reference

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar genesis-p2p.jar

# Health check
curl http://localhost:8080/health

# Graceful shutdown
kill -TERM <pid>

# View logs
tail -f logs/app.log

# Scale (Docker)
docker-compose up -d --scale node=5
```

### Useful Links

- Official Documentation: `docs/`
- API Reference: `target/site/apidocs/`
- Configuration Examples: `config/`
- Docker Hub: `docker pull genesis-p2p:2.0.0`

---

**Document Version**: 1.0  
**Last Updated**: December 28, 2025  
**Classification**: Operations  
**Support**: See repository issues for support

---

*For advanced topics, refer to the Technical Deep-Dive report.*

