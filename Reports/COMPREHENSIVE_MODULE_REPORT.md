# Genesis P2P Framework - Comprehensive Module Report
**تقرير شامل لجميع الـ Modules**
**التاريخ:** 2026-02-04
**الإصدار:** 2.0

---

## 📋 الفهرس

1. [نظرة عامة](#نظرة-عامة)
2. [Application Module](#1-application-module)
3. [Core Module](#2-core-module)
4. [Transport Module](#3-transport-module)
5. [Security Module](#4-security-module)
6. [Discovery Module](#5-discovery-module)
7. [NAT Module](#6-nat-module)
8. [Protocol Module](#7-protocol-module)
9. [Events Module](#8-events-module)
10. [Observability Module](#9-observability-module)
11. [Storage Module](#10-storage-module)
12. [Util Module](#11-util-module)
13. [مصفوفة العلاقات بين الـ Modules](#مصفوفة-العلاقات-بين-الـ-modules)
14. [الـ Flow الكامل](#الـ-flow-الكامل)
15. [ما هو الناقص](#ما-هو-الناقص)

---

## نظرة عامة

Genesis P2P Framework هو إطار عمل متكامل لبناء شبكات Peer-to-Peer. يتكون من **11 module** رئيسي يعملون معاً لتوفير:

- اكتشاف الأقران (Peer Discovery)
- نقل الرسائل بأمان (Secure Message Transport)
- إدارة الاتصالات (Connection Management)
- اجتياز NAT (NAT Traversal)
- المراقبة والتسجيل (Observability)

### إحصائيات المشروع
| المقياس | العدد |
|---------|-------|
| إجمالي الـ Modules | 11 |
| إجمالي الـ Classes | ~200+ |
| إجمالي الـ Interfaces | ~30+ |
| أنماط التصميم المستخدمة | 15+ |

---

## 1. Application Module
**المسار:** `com.genesis.p2p.application`

### الوصف
نقطة الدخول الرئيسية للـ Framework. يحتوي على `Node` الذي يربط جميع المكونات معاً.

### Classes

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **Node.java** | المنسق المركزي - يربط جميع الـ subsystems | يستخدم: PeerManager, MessageHandler, TransportFactory, SecurityFacade, DiscoveryFactory, EventBus, ProtocolLayer |
| **NodeBuilder.java** | Builder Pattern لإنشاء Node | ينشئ: Node, NodeConfig, SecurityConfig, ProtocolConfig |
| **NodeConfig.java** | Record يحمل إعدادات الـ Node | مستخدم من: Node, NodeBuilder |
| **Main.java** | نقطة الدخول التنفيذية | يستخدم: NodeBuilder, Node |
| **ConfigLoader.java** | تحميل الإعدادات من JSON | مستخدم من: Main |
| **ShutdownHooks.java** | إدارة إيقاف التشغيل | مستخدم من: Node |
| **HealthCheckService.java** | فحص صحة النظام | يستخدم: HealthCheck, ThreadPoolFactory |
| **ClusterManager.java** | إدارة التجمعات | يستخدم: Node, PeerManager |
| **ClusterCommands.java** | أوامر التجمع | يستخدم: ClusterManager |
| **INodeLifecycle.java** | Interface لدورة حياة الـ Node | مُنفَّذ بواسطة: Node |

### الوظائف الرئيسية في Node.java

```java
// Lifecycle
void start()                    // بدء جميع الخدمات
void stop()                     // إيقاف جميع الخدمات بشكل graceful

// Messaging
void sendMessage(Message, Peer) // إرسال رسالة لـ peer
void broadcast(Message)         // بث رسالة لجميع الـ peers

// Peer Management
Collection<Peer> getPeers()     // الحصول على جميع الـ peers
void connectToPeer(String)      // الاتصال بـ peer جديد

// State
State getState()                // حالة الـ Node الحالية
```

---

## 2. Core Module
**المسار:** `com.genesis.p2p.core`

### الوصف
يحتوي على البنية الأساسية: الرسائل، الأقران، ومعالجة الرسائل.

### Sub-packages

#### 2.1 Root Classes

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **Message.java** | Record يمثل رسالة P2P | يحتوي: MessageHeader, MessageBody |
| **MessageHeader.java** | بيانات وصفية للرسالة | جزء من: Message |
| **MessageBody.java** | محتوى الرسالة | جزء من: Message |
| **Peer.java** | Record يمثل نظير في الشبكة | مستخدم في: PeerManager, Discovery |
| **MessageHandler.java** | معالج الرسائل المركزي | يستخدم: ProcessorRegistry, BackpressureManager, DeduplicationService, CircuitBreakerManager |

#### 2.2 Peer Sub-package (`core/peer/`)

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **PeerManager.java** | Facade لإدارة الأقران | يستخدم: PeerStore, PeerReputationService, PeerQueryService, PeerHealthMonitor, PeerCleanupService |
| **PeerStore.java** | تخزين الأقران (LRUCache) | مستخدم من: PeerManager |
| **PeerReputationService.java** | إدارة السمعة | مستخدم من: PeerManager |
| **PeerQueryService.java** | استعلامات متقدمة عن الأقران | مستخدم من: PeerManager |
| **PeerHealthMonitor.java** | مراقبة صحة الأقران | مستخدم من: PeerManager |
| **PeerStateMachine.java** | آلة حالة الـ Peer | مستخدم من: PeerManager |
| **PeerCleanupService.java** | تنظيف الأقران المنتهية | مستخدم من: PeerManager |
| **PeerEventBus.java** | ناقل أحداث الأقران | مستخدم من: PeerManager |
| **PeerConnectionOrchestrator.java** | تنسيق الاتصالات | يستخدم: PeerManager, ITransport, HandshakeProcessor, SecureChannelNegotiator |

#### 2.3 Handlers Sub-package (`core/handlers/`)

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **MessageHandlerConfig.java** | إعدادات MessageHandler | مستخدم من: MessageHandler |
| **ProcessingContext.java** | سياق معالجة الرسالة | يُمرر للـ Processors |
| **ProcessingResult.java** | نتيجة المعالجة | يُرجع من MessageHandler |
| **FailedMessage.java** | رسالة فاشلة | مستخدم في: DeadLetterQueue |

#### 2.4 Processors Sub-package (`core/handlers/processors/`)

| Class | الوظيفة |
|-------|---------|
| **MessageProcessor.java** | Interface لجميع المعالجات |
| **ProcessorRegistry.java** | سجل المعالجات |
| **SystemProcessorFactory.java** | مصنع معالجات النظام |

##### Discovery Processors
- `DiscoveryPingProcessor` - معالجة PING
- `BootstrapResponseProcessor` - معالجة استجابة Bootstrap

##### System Processors
- `PingProcessor`, `PongProcessor` - heartbeat
- `HelloProcessor`, `WelcomeProcessor` - تحية
- `HeartbeatProcessor` - نبضات القلب
- `GoodbyeProcessor` - وداع

##### Alert Processors
- `NodeHealthAlertProcessor` - تنبيهات الصحة
- `PeerMisbehaviorAlertProcessor` - تنبيهات سوء السلوك
- `RateLimitAlertProcessor` - تنبيهات تجاوز الحد

##### Security Processors
- `KeyExchangeInitProcessor` - بدء تبادل المفاتيح
- `KeyExchangeCompleteProcessor` - إتمام تبادل المفاتيح

#### 2.5 Handler Components

| Package | الوظيفة |
|---------|---------|
| `async/` | معالجة غير متزامنة (AsyncMessageProcessor) |
| `backpressure/` | إدارة الضغط (BackpressureManager) |
| `circuit/` | Circuit Breaker (CircuitBreakerManager) |
| `dedup/` | إزالة التكرار (DeduplicationService) |
| `dlq/` | Dead Letter Queue |
| `ratelimit/` | تحديد المعدل (RateLimitManager) |
| `retry/` | إعادة المحاولة (RetryManager) |

---

## 3. Transport Module
**المسار:** `com.genesis.p2p.transport`

### الوصف
طبقة النقل - تدعم TCP, UDP, WebSocket.

### Sub-packages

#### 3.1 Core (`transport/core/`)

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **ITransport.java** | Interface للنقل | مُنفَّذ بواسطة: TcpTransport, UdpTransport, WebSocketTransport |
| **AbstractTransport.java** | Base class للنقل | موروث بواسطة: جميع الـ Transports |
| **TransportFactory.java** | مصنع النقل | ينشئ: TcpTransport, UdpTransport, WebSocketTransport |
| **TransportConfig.java** | إعدادات النقل | مستخدم من: Transports |
| **TransportState.java** | Enum لحالة النقل | - |
| **TransportType.java** | Enum لنوع النقل (TCP/UDP/WS) | - |
| **TransportStats.java** | إحصائيات النقل | - |
| **ITransportEnvelopeHandler.java** | Interface لمعالجة الرسائل الواردة | مُنفَّذ بواسطة: SecurityGateway |

#### 3.2 TCP (`transport/tcp/`)

| Class | الوظيفة |
|-------|---------|
| **TcpTransport.java** | نقل TCP كامل |
| **TcpConnection.java** | اتصال TCP واحد |
| **TcpErrorClassification.java** | تصنيف أخطاء TCP |

#### 3.3 UDP (`transport/udp/`)

| Class | الوظيفة |
|-------|---------|
| **UdpTransport.java** | نقل UDP مع padding |

#### 3.4 WebSocket (`transport/ws/`)

| Class | الوظيفة |
|-------|---------|
| **WebSocketTransport.java** | نقل WebSocket |
| **WebSocketConnection.java** | اتصال WebSocket واحد |

### الوظائف الرئيسية

```java
// ITransport Interface
void start()                                    // بدء الخدمة
void stop()                                     // إيقاف الخدمة
CompletableFuture<Void> send(Message, InetSocketAddress) // إرسال رسالة
void setEnvelopeHandler(ITransportEnvelopeHandler)       // تعيين معالج الرسائل
```

---

## 4. Security Module
**المسار:** `com.genesis.p2p.security`

### الوصف
طبقة الأمان - تشفير، مصادقة، إدارة الجلسات.

### Sub-packages

| Package | الوظيفة | Classes الرئيسية |
|---------|---------|-----------------|
| **facade/** | Facade Pattern للأمان | `SecurityFacade.java` |
| **factory/** | إنشاء مكونات الأمان | `SecurityFactory.java` |
| **gateway/** | بوابة الأمان | `SecurityGateway.java`, `SecurityGatewayBuilder.java` |
| **config/** | إعدادات الأمان | `SecurityConfig.java` |
| **crypto/** | التشفير | `AesCryptoProvider.java` |
| **ecdh/** | تبادل المفاتيح ECDH | `EcdhKeyExchange.java`, `EcdhKeyDerivation.java` |
| **hmac/** | HMAC | `HmacService.java` |
| **session/** | إدارة الجلسات | `SecureSession.java`, `SecureSessionManager.java` |
| **channel/** | القنوات الآمنة | `SecureChannel.java`, `SecureChannelNegotiator.java`, `KeyExchangeInit.java`, `KeyExchangeComplete.java` |
| **keys/** | إدارة المفاتيح | `KeyManager.java` |
| **trust/** | إدارة الثقة | `TrustManager.java` |
| **signature/** | التوقيعات | `SignatureService.java` |
| **replay/** | حماية من الإعادة | - |
| **policy/** | سياسات الأمان | `MessageSecurityPolicy.java` |
| **cert/** | الشهادات | `CertificateManager.java`, `CertificateValidator.java` |
| **api/** | Interfaces | `ICryptoProvider`, `ISessionManager`, `ISignatureService`, `ITrustManager`, `IKeyManager` |

### الوظائف الرئيسية في SecurityFacade

```java
// Encryption
byte[] encrypt(byte[] data, String peerId)      // تشفير
byte[] decrypt(byte[] data, String peerId)      // فك التشفير

// Key Exchange (ECDH)
byte[] initiateKeyExchange(String peerId)       // بدء تبادل المفاتيح
byte[] completeKeyExchange(String peerId, byte[] remotePublicKey) // إتمام التبادل

// Signing
byte[] sign(byte[] data)                        // توقيع
boolean verify(byte[] data, byte[] signature, String peerId) // تحقق

// Session Management
boolean hasSession(String peerId)               // هل يوجد جلسة
void invalidateSession(String peerId)           // إبطال الجلسة
```

---

## 5. Discovery Module
**المسار:** `com.genesis.p2p.discovery`

### الوصف
اكتشاف الأقران في الشبكة - Multicast, Broadcast, Bootstrap.

### Classes

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **IDiscoveryService.java** | Interface للاكتشاف | مُنفَّذ بواسطة: جميع خدمات الاكتشاف |
| **AbstractDiscoveryService.java** | Base class | موروث بواسطة: MulticastDiscovery, BroadcastDiscovery, BootstrapDiscovery |
| **DiscoveryFactory.java** | مصنع الاكتشاف | ينشئ: جميع خدمات الاكتشاف |
| **MulticastDiscovery.java** | اكتشاف Multicast | يستخدم: PeerManager, MetricsRegistry |
| **BroadcastDiscovery.java** | اكتشاف Broadcast | يستخدم: PeerManager, MetricsRegistry |
| **BootstrapDiscovery.java** | اكتشاف Bootstrap | يستخدم: PeerManager, MetricsRegistry |
| **CompositeDiscovery.java** | تجميع خدمات متعددة | يحتوي: قائمة IDiscoveryService |
| **HybridDiscovery.java** | استراتيجية Fallback | يستخدم: CompositeDiscovery |
| **DiscoveryConfig.java** | إعدادات الاكتشاف | مستخدم من: DiscoveryFactory |
| **IDiscoveryListener.java** | Interface للاستماع | - |
| **NatAwareDiscoveryContext.java** | سياق NAT-aware | مستخدم من: AbstractDiscoveryService |

### الوظائف الرئيسية

```java
// IDiscoveryService Interface
void start()                                    // بدء الاكتشاف
void stop()                                     // إيقاف الاكتشاف
CompletableFuture<List<Peer>> discoverPeers()  // اكتشاف الأقران
void announceSelf()                             // الإعلان عن الذات
```

---

## 6. NAT Module
**المسار:** `com.genesis.p2p.nat`

### الوصف
اجتياز NAT - اكتشاف نوع NAT، STUN.

### Classes

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **INatTraversalService.java** | Interface لـ NAT | مُنفَّذ بواسطة: AbstractNatTraversalService |
| **AbstractNatTraversalService.java** | Base class | موروث بواسطة: StunNatDetector |
| **NatDetectionFactory.java** | مصنع NAT | ينشئ: StunNatDetector |
| **NatResolutionService.java** | حل استراتيجية NAT | يستخدم: INatTraversalService |
| **NatAwareDiscovery.java** | اكتشاف NAT-aware | يمتد: AbstractDiscoveryService |
| **NatType.java** | Enum لأنواع NAT | - |
| **NatConfig.java** | إعدادات NAT | - |
| **NatUtils.java** | أدوات مساعدة | - |
| **ConnectionStrategy.java** | استراتيجية الاتصال | - |

### STUN Sub-package (`nat/stun/`)

| Class | الوظيفة |
|-------|---------|
| **StunNatDetector.java** | كشف NAT عبر STUN |
| **StunClient.java** | عميل STUN |
| **StunMessageTypes.java** | أنواع رسائل STUN |

### أنواع NAT المدعومة

```java
enum NatType {
    OPEN,              // لا NAT
    FULL_CONE,         // Full Cone NAT
    RESTRICTED_CONE,   // Restricted Cone NAT
    PORT_RESTRICTED,   // Port Restricted NAT
    SYMMETRIC,         // Symmetric NAT (الأصعب)
    UNKNOWN            // غير معروف
}
```

---

## 7. Protocol Module
**المسار:** `com.genesis.p2p.protocol`

### الوصف
طبقة البروتوكول - encoding, framing, validation, compression.

### Sub-packages

| Package | الوظيفة | Classes الرئيسية |
|---------|---------|-----------------|
| **Root** | - | `ProtocolLayer.java`, `ProtocolException.java` |
| **config/** | إعدادات | `ProtocolConfig.java` |
| **codec/** | Encoding/Decoding | `MessageCodec.java`, `JsonCodec.java` |
| **model/** | نماذج البيانات | `Frame.java`, `Envelope.java`, `ProtocolVersion.java` |
| **compression/** | ضغط | `CompressionCodec.java`, `GzipCodec.java` |
| **handshake/** | المصافحة | `HandshakeProcessor.java`, `HandshakeRequest.java`, `HandshakeResponse.java`, `HandshakeReject.java` |
| **negotiation/** | تفاوض البروتوكول | `ProtocolNegotiationService.java` |
| **validator/** | التحقق | `ProtocolValidator.java`, `ValidationRule.java` |

### الوظائف الرئيسية في ProtocolLayer

```java
// Encoding
byte[][] encodeToFrames(Message message)        // تحويل رسالة إلى frames

// Decoding
void receiveFrame(byte[] frameData)             // استلام frame
boolean isMessageComplete(UUID messageId)       // هل الرسالة مكتملة
Message reassembleMessage(UUID messageId)       // إعادة تجميع الرسالة

// Lifecycle
void start()
void stop()
```

---

## 8. Events Module
**المسار:** `com.genesis.p2p.events`

### الوصف
نظام الأحداث - Publish/Subscribe pattern.

### Sub-packages

#### 8.1 Core (`events/core/`)

| Class | الوظيفة |
|-------|---------|
| **IEvent.java** | Interface للحدث |
| **IEventBus.java** | Interface للناقل |
| **IEventListener.java** | Interface للمستمع |
| **EventBus.java** | تنفيذ ناقل الأحداث |
| **AbstractEvent.java** | Base class للأحداث |
| **GenericEvent.java** | حدث عام |
| **Subscription.java** | اشتراك |
| **IEventFilter.java** | فلترة الأحداث |
| **IEventTransformer.java** | تحويل الأحداث |
| **LambdaEventListener.java** | مستمع Lambda |

#### 8.2 Exceptions (`events/exceptions/`)

- `EventException.java`
- `EventBusClosedException.java`
- `EventPublishException.java`
- `EventValidationException.java`
- `ListenerExecutionException.java`
- `NullEventException.java`

### الوظائف الرئيسية

```java
// EventBus
Subscription subscribe(String eventType, IEventListener listener)
void publish(IEvent event)
void publishAsync(IEvent event)
void unsubscribe(String eventType, IEventListener listener)
```

---

## 9. Observability Module
**المسار:** `com.genesis.p2p.observability`

### الوصف
المراقبة - Logging, Metrics, Tracing, Health.

### Sub-packages

| Package | الوظيفة | Classes الرئيسية |
|---------|---------|-----------------|
| **Root** | Facade | `ObservabilityFacade.java` |
| **logging/** | التسجيل المهيكل | `NodeLogger.java` |
| **metrics/** | المقاييس | `MetricsRegistry.java`, `TaggedMetricsRegistry.java` |
| **tracing/** | التتبع | `MessageLifecycleTracker.java` |
| **context/** | السياق الموزع | `ObservabilityContext.java` |
| **message/** | تسجيل الرسائل | `MessageLogger.java`, `MessageReplayHandler.java` |
| **health/** | الصحة | `HealthIndicator.java`, `ComponentHealthRegistry.java` |
| **config/** | الإعدادات | `MonitoringConfig.java` |

### الوظائف الرئيسية

```java
// MetricsRegistry
void incrementCounter(String name)
void setGauge(String name, long value)
void recordTimer(String name, long durationMs)
MetricsSnapshot getSnapshot()

// NodeLogger
void info(String message, Object... args)
void error(String message, Exception e, Object... args)
void debug(String message, Object... args)
```

---

## 10. Storage Module
**المسار:** `com.genesis.p2p.storage`

### الوصف
التخزين الدائم - Peers, Dead Letter Queue, Messages.

### Classes

| Class | الوظيفة | العلاقات |
|-------|---------|----------|
| **PersistenceFacade.java** | Facade للتخزين | يستخدم: PeerStorePersistent, DeadLetterQueuePersistent |
| **KVStore.java** | Interface للتخزين Key-Value | مُنفَّذ بواسطة: FileKVStore |
| **FileKVStore.java** | تخزين ملفات | - |
| **PeerStorePersistent.java** | تخزين الأقران | - |
| **DeadLetterQueuePersistent.java** | تخزين الرسائل الفاشلة | - |

### Message Sub-package (`storage/message/`)

| Class | الوظيفة |
|-------|---------|
| **MessagePersistenceStore.java** | تخزين الرسائل |
| **MessageSerializer.java** | تسلسل الرسائل |
| **WriteAheadLog.java** | سجل WAL |
| **PersistedMessage.java** | رسالة محفوظة |
| **MessageState.java** | حالة الرسالة |

### الوظائف الرئيسية

```java
// PersistenceFacade
void savePeer(Peer peer)
List<Peer> loadAllPeers()
void addToDeadLetterQueue(Message, String reason, int retryCount)
List<DLQEntry> getAllDeadLetters()
boolean isAvailable()
```

---

## 11. Util Module
**المسار:** `com.genesis.p2p.util`

### الوصف
أدوات مساعدة مشتركة.

### Sub-packages

| Package | الوظيفة | Classes الرئيسية |
|---------|---------|-----------------|
| **threading/** | إدارة الـ Threads | `ThreadPoolManager.java`, `ThreadPoolFactory.java`, `NamedThreadFactory.java` |
| **collections/** | مجموعات خاصة | `LRUCache.java`, `EvictingQueue.java`, `CollectionsUtils.java` |
| **resource/** | إدارة الموارد | `ResourceLeakDetector.java`, `AutoCloser.java`, `Closer.java` |
| **constants/** | الثوابت | `TimeoutConstants.java` |
| **config/** | الإعدادات | `PerformanceConfig.java` |
| **common/** | عام | `Time.java`, `Bytes.java`, `HexUtils.java` |
| **net/** | الشبكة | `NetworkUtils.java`, `SocketUtils.java`, `UrlParser.java` |
| **io/** | IO | `IOStreams.java`, `ByteBufferPool.java` |
| **crypto/** | التشفير | `RandomUtils.java`, `Base64Utils.java` |

---

## مصفوفة العلاقات بين الـ Modules

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              APPLICATION (Node)                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐│
│  │                        Central Orchestrator                                      ││
│  └─────────────────────────────────────────────────────────────────────────────────┘│
└───────┬───────────┬───────────┬───────────┬───────────┬───────────┬───────────┬─────┘
        │           │           │           │           │           │           │
        ▼           ▼           ▼           ▼           ▼           ▼           ▼
┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐
│   CORE    │ │ TRANSPORT │ │ SECURITY  │ │ DISCOVERY │ │   NAT     │ │ PROTOCOL  │
│           │ │           │ │           │ │           │ │           │ │           │
│ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │
│ │Peer   │ │ │ │TCP    │ │ │ │Facade │ │ │ │Multi  │ │ │ │STUN   │ │ │ │Layer  │ │
│ │Manager│ │ │ │Trans  │ │ │ │       │ │ │ │cast   │ │ │ │Detect │ │ │ │       │ │
│ └───┬───┘ │ │ └───┬───┘ │ │ └───┬───┘ │ │ └───┬───┘ │ │ └───┬───┘ │ │ └───┬───┘ │
│     │     │ │     │     │ │     │     │ │     │     │ │     │     │ │     │     │
│ ┌───▼───┐ │ │ ┌───▼───┐ │ │ ┌───▼───┐ │ │ ┌───▼───┐ │ │ ┌───▼───┐ │ │ ┌───▼───┐ │
│ │Message│ │ │ │UDP    │ │ │ │Channel│ │ │ │Broad  │ │ │ │Resol  │ │ │ │Hand   │ │
│ │Handler│ │ │ │Trans  │ │ │ │Negot  │ │ │ │cast   │ │ │ │ution  │ │ │ │shake  │ │
│ └───────┘ │ │ └───────┘ │ │ └───────┘ │ │ └───────┘ │ │ └───────┘ │ │ └───────┘ │
│           │ │           │ │           │ │           │ │           │ │           │
│ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │ │ ┌───────┐ │ │           │ │ ┌───────┐ │
│ │Process│ │ │ │Web    │ │ │ │Gateway│ │ │ │Boot   │ │ │           │ │ │Codec  │ │
│ │ors    │ │ │ │Socket │ │ │ │       │ │ │ │strap  │ │ │           │ │ │       │ │
│ └───────┘ │ │ └───────┘ │ │ └───────┘ │ │ └───────┘ │ │           │ │ └───────┘ │
└───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘
        │           │           │           │           │           │
        └───────────┴───────────┴─────┬─────┴───────────┴───────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌───────────────┐           ┌───────────────┐           ┌───────────────┐
│    EVENTS     │           │ OBSERVABILITY │           │    STORAGE    │
│               │           │               │           │               │
│ ┌───────────┐ │           │ ┌───────────┐ │           │ ┌───────────┐ │
│ │ EventBus  │ │           │ │ Metrics   │ │           │ │Persistence│ │
│ └───────────┘ │           │ │ Registry  │ │           │ │ Facade    │ │
│               │           │ └───────────┘ │           │ └───────────┘ │
│ ┌───────────┐ │           │ ┌───────────┐ │           │ ┌───────────┐ │
│ │Subscribers│ │           │ │ Logger    │ │           │ │ KVStore   │ │
│ └───────────┘ │           │ └───────────┘ │           │ └───────────┘ │
└───────────────┘           └───────────────┘           └───────────────┘
                                      │
                                      ▼
                            ┌───────────────┐
                            │     UTIL      │
                            │               │
                            │ Threading     │
                            │ Collections   │
                            │ Resources     │
                            │ Constants     │
                            └───────────────┘
```

### جدول العلاقات

| Module | يعتمد على | يُستخدم بواسطة |
|--------|-----------|----------------|
| **Application** | Core, Transport, Security, Discovery, NAT, Protocol, Events, Observability, Storage | - |
| **Core** | Events, Observability, Storage, Util | Application, Discovery, NAT, Protocol |
| **Transport** | Core, Security, Protocol, Observability, Util | Application, Core |
| **Security** | Observability, Util | Application, Transport, Core, Discovery |
| **Discovery** | Core, Observability, Util | Application, NAT |
| **NAT** | Core, Discovery, Util | Application, Core |
| **Protocol** | Core, Observability, Util | Application, Transport |
| **Events** | Observability, Util | Application, Core |
| **Observability** | Util | الكل |
| **Storage** | Core, Observability, Util | Application, Core |
| **Util** | - | الكل |

---

## الـ Flow الكامل

### 1. Node Startup Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                             NODE STARTUP SEQUENCE                                │
└─────────────────────────────────────────────────────────────────────────────────┘

NodeBuilder.build()
     │
     ▼
┌─────────────┐
│ Phase 1:    │
│ Foundation  │──► MetricsRegistry ──► ThreadPoolManager ──► RateLimitManager
└──────┬──────┘                                              ──► PersistenceFacade
       │
       ▼
┌─────────────┐
│ Phase 2:    │
│ Events      │──► EventBus ──► Subscribe to system events
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Phase 3:    │
│ Security    │──► SecurityFacade ──► SecureChannelNegotiator ──► ProtocolValidator
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Phase 4:    │
│ Peers       │──► PeerManager ──► PeerEventBus bridge to EventBus
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Phase 5:    │
│ Transport   │──► ProtocolLayer ──► TransportFactory
└──────┬──────┘                    ──► TcpTransport ──► UdpTransport
       │
       ▼
┌─────────────┐
│ Phase 6:    │
│ Processors  │──► ProcessorRegistry ──► MessageHandler
└──────┬──────┘   ──► SystemProcessors ──► DiscoveryProcessors ──► SecurityProcessors
       │
       ▼
┌─────────────┐
│ Phase 6.5:  │
│ Alerts      │──► NodeHealthAlertProcessor ──► PeerMisbehaviorAlertProcessor
└──────┬──────┘   ──► RateLimitAlertProcessor
       │
       ▼
┌─────────────┐
│ Phase 7:    │
│ Discovery   │──► DiscoveryFactory.createComposite()
└──────┬──────┘   ──► MulticastDiscovery + BroadcastDiscovery
       │
       ▼
┌─────────────┐
│ Phase 7.5:  │
│ Orchestrate │──► PeerConnectionOrchestrator (NAT-aware connections)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Phase 8:    │
│ Wiring      │──► SecurityGateway ──► Wire transports through gateway
└─────────────┘
```

### 2. Peer Discovery Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            PEER DISCOVERY FLOW                                   │
└─────────────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────┐
                    │  Discovery Start    │
                    └──────────┬──────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
          ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│   Multicast     │  │   Broadcast     │  │   Bootstrap     │
│   Discovery     │  │   Discovery     │  │   Discovery     │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                    │                    │
         │         ┌──────────┴──────────┐         │
         │         │  Send Announcement  │         │
         │         │  (UDP Packet)       │         │
         │         └──────────┬──────────┘         │
         │                    │                    │
         │                    ▼                    │
         │         ┌─────────────────────┐         │
         │         │  Receive Response   │         │
         │         │  (Peer Info)        │         │
         │         └──────────┬──────────┘         │
         │                    │                    │
         └────────────────────┼────────────────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  NAT Filtering      │
                   │  (NatAwareContext)  │
                   └──────────┬──────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  PeerManager        │
                   │  .upsertPeer()      │
                   └──────────┬──────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  PeerEventBus       │
                   │  .firePeerAdded()   │
                   └──────────┬──────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  EventBus           │
                   │  "peer.discovered"  │
                   └──────────┬──────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  Connection         │
                   │  Orchestrator       │
                   └─────────────────────┘
```

### 3. Secure Connection Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        SECURE CONNECTION FLOW                                    │
└─────────────────────────────────────────────────────────────────────────────────┘

  Node A                                                              Node B
    │                                                                    │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │               KEY EXCHANGE PHASE                             │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                    │
    │  1. Generate ephemeral ECDH keypair                               │
    │  2. Sign ephemeral public key with identity key                   │
    │                                                                    │
    ├──────── KEY_EXCHANGE_INIT ─────────────────────────────────────────►
    │         (ephemeral_pub, signature)                                 │
    │                                                                    │
    │                                                 3. Verify signature│
    │                                                 4. Generate ECDH   │
    │                                                 5. Derive shared   │
    │                                                    secret (HKDF)   │
    │                                                                    │
    ◄───────────────────────────────── KEY_EXCHANGE_COMPLETE ────────────┤
    │                                   (ephemeral_pub, signature)       │
    │                                                                    │
    │  6. Verify signature                                              │
    │  7. Derive shared secret (HKDF)                                   │
    │                                                                    │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │               HANDSHAKE PHASE                                │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                    │
    ├──────── HANDSHAKE_REQUEST (encrypted) ─────────────────────────────►
    │         (nodeId, version, capabilities)                            │
    │                                                                    │
    ◄───────────────────────────── HANDSHAKE_RESPONSE (encrypted) ───────┤
    │                               (nodeId, version, capabilities)      │
    │                                                                    │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │               SECURE SESSION ESTABLISHED                     │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                    │
    ├══════════ ENCRYPTED MESSAGES ══════════════════════════════════════►
    ◄══════════ ENCRYPTED MESSAGES ══════════════════════════════════════┤
```

### 4. Message Processing Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         MESSAGE PROCESSING FLOW                                  │
└─────────────────────────────────────────────────────────────────────────────────┘

  Incoming Packet (TCP/UDP)
          │
          ▼
┌─────────────────────┐
│  ITransport         │
│  (TcpTransport/     │
│   UdpTransport)     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  SecurityGateway    │──► Policy check (message type allowed?)
│                     │──► Decrypt if encrypted
│                     │──► Verify HMAC
│                     │──► Replay protection
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  ProtocolLayer      │──► Decode from bytes
│                     │──► Reassemble frames
│                     │──► Decompress if needed
│                     │──► Validate protocol
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  MessageHandler     │
│  .handleMessage()   │
└──────────┬──────────┘
           │
    ┌──────┼──────┬──────────┬──────────┬───────────┐
    │      │      │          │          │           │
    ▼      ▼      ▼          ▼          ▼           ▼
┌──────┐┌──────┐┌──────┐┌──────────┐┌──────────┐┌───────────┐
│Valid ││Dedup ││Back  ││Circuit   ││Update    ││Async      │
│ation ││Check ││press ││Breaker   ││Peer Info ││Processing │
└──────┘└──────┘└──────┘└──────────┘└──────────┘└─────┬─────┘
                                                      │
                                                      ▼
                                          ┌───────────────────┐
                                          │ ProcessorRegistry │
                                          │ .process()        │
                                          └─────────┬─────────┘
                                                    │
                    ┌───────────────────────────────┼───────────────────────────────┐
                    │                               │                               │
                    ▼                               ▼                               ▼
          ┌─────────────────┐           ┌─────────────────┐           ┌─────────────────┐
          │ System          │           │ Discovery       │           │ Application     │
          │ Processors      │           │ Processors      │           │ Processors      │
          │ (PING,PONG..)   │           │ (PEER_ADVERT..) │           │ (Custom)        │
          └─────────────────┘           └─────────────────┘           └─────────────────┘
```

---

## ما هو الناقص

### 🔴 الناقص الحرج (Critical Missing)

| الميزة | الوصف | التأثير |
|--------|-------|---------|
| **TURN Relay** | دعم relay للـ Symmetric NAT | لا يمكن الاتصال ببعض أنواع NAT |
| **DHT (Distributed Hash Table)** | تخزين موزع للـ peers | يعتمد على bootstrap servers |
| **Peer Routing** | توجيه الرسائل عبر peers وسيطة | رسائل مباشرة فقط |

### 🟡 الناقص المتوسط (Medium Missing)

| الميزة | الوصف | الملفات المتأثرة |
|--------|-------|-----------------|
| **Key Rotation Interface** | `ISecureSession.rotateKeys()` غير موجود | SecurityFacade.java |
| **Message Acknowledgment** | تأكيد استلام الرسائل | MessageHandler.java |
| **Peer Blacklisting** | قائمة سوداء للـ peers | PeerManager.java |
| **Rate Limiting per Peer** | تحديد المعدل لكل peer | RateLimitManager.java |
| **Message Priority Queue** | أولويات الرسائل | MessageHandler.java |

### 🟢 تحسينات مستقبلية (Nice to Have)

| الميزة | الوصف |
|--------|-------|
| **QUIC Transport** | بروتوكول QUIC (UDP + TLS) |
| **gRPC Integration** | دعم gRPC |
| **Prometheus Metrics** | تصدير metrics لـ Prometheus |
| **OpenTelemetry** | تتبع موزع كامل |
| **WebRTC** | دعم WebRTC للمتصفحات |
| **Mobile SDK** | SDK للـ Android/iOS |

### 📝 TODOs في الكود

| الموقع | الوصف |
|--------|-------|
| `PeerConnectionOrchestrator.java:704` | Implement TURN relay |
| `SecurityFacade.java:478` | Add needsKeyRotation() to ISecureSession |
| `SecurityFacade.java:494` | Add rotateKeys() to ISecureSession |

### 🔧 تحسينات معمارية موصى بها

1. **Full Dependency Injection** - استخدام DI container (مثل Guice)
2. **Interface Segregation** - تقسيم interfaces كبيرة
3. **Event Sourcing** - للـ message persistence
4. **CQRS** - فصل القراءة عن الكتابة

---

## الخلاصة

Genesis P2P Framework هو إطار عمل متكامل وجاهز للإنتاج مع:

✅ **11 Modules** متكاملة
✅ **200+ Classes** موثقة
✅ **15+ Design Patterns** مطبقة
✅ **Full Lifecycle Management**
✅ **Security (ECDH + AES-GCM)**
✅ **NAT Traversal (STUN)**
✅ **Multiple Transport Options**
✅ **Comprehensive Observability**

الميزات الناقصة الرئيسية هي **TURN Relay** و **DHT** التي تحتاج تنفيذ لدعم جميع سيناريوهات الشبكات.

---

*Generated: 2026-02-04*
*Framework Version: 2.0*

