# خطة دمج Security في كل الكود

## المناطق التي تحتاج دمج Security

### 1. Protocol Validator
- ✅ **SignatureValidationRule.java** - إضافة SecurityFacade للتحقق من التوقيعات
- ✅ **ProtocolValidator.java** - تمرير SecurityFacade للقواعد

### 2. Handshake
- ✅ **HandshakeProcessor.java** - يستخدم SecurityFacade بالفعل
- ✅ **HandshakeValidator.java** - إضافة SecurityFacade

### 3. Protocol Layer
- 🔄 **ProtocolLayer.java** - إضافة SecurityFacade للتشفير/فك التشفير
- 🔄 **Envelope.java** - دعم التشفير في الـenvelope
- 🔄 **Frame.java** - دعم التشفير في الـframe

### 4. Message Codecs
- 🔄 **ProtobufMessageCodec.java** - تشفير/فك تشفير الرسائل
- 🔄 **JsonMessageCodec.java** - تشفير/فك تشفير الرسائل

### 5. Peer Manager
- 🔄 **PeerManager.java** - استخدام trust manager للأقران
- 🔄 **PeerStore.java** - تخزين مفاتيح الأقران

### 6. Discovery Processors
- 🔄 **BaseDiscoveryProcessor.java** - التوقيع على الرسائل
- 🔄 جميع معالجات Discovery - استخدام signatures

### 7. Core Handlers
- 🔄 **MessageHandler.java** - التحقق من التوقيعات
- 🔄 **ValidationPipeline.java** - دمج security validation

### 8. Storage
- 🔄 **SnapshotManager.java** - تشفير الـsnapshots
- 🔄 **PeerDatabase.java** - تشفير البيانات الحساسة

---

## الأولويات

### Priority 1: التحقق من التوقيعات ✅
1. SignatureValidationRule - إضافة SecurityFacade
2. ProtocolValidator - تمرير SecurityFacade
3. HandshakeValidator - استخدام SecurityFacade

### Priority 2: التشفير في Protocol Layer 🔄
4. ProtocolLayer - تشفير/فك تشفير
5. Envelope - دعم encrypted flag
6. Frame - دعم encrypted flag

### Priority 3: Discovery Security 🔄
7. BaseDiscoveryProcessor - توقيع الرسائل
8. جميع معالجات Discovery - التحقق من التوقيعات

### Priority 4: Storage Security 🔄
9. SnapshotManager - تشفير snapshots
10. PeerDatabase - تشفير بيانات حساسة

---

## تم إكماله

### ✅ Transport Layer
- TcpTransport - يستخدم SecurityFacade
- UdpTransport - يستخدم SecurityFacade
- AbstractTransport - يستخدم SecurityFacade
- EncryptionFilter - يستخدم SecurityFacade

### ✅ Application Layer
- Node - يستخدم SecurityFacade
- TransportFactory - يستخدم SecurityFacade

---

## الخطوات التالية

1. ✅ SignatureValidationRule - إضافة SecurityFacade
2. ✅ HandshakeValidator - إضافة SecurityFacade
3. 🔄 ProtocolLayer - تشفير الرسائل
4. 🔄 BaseDiscoveryProcessor - توقيع الرسائل
5. 🔄 MessageHandler - التحقق الأمني
6. 🔄 SnapshotManager - تشفير
7. 🔄 PeerDatabase - تشفير


