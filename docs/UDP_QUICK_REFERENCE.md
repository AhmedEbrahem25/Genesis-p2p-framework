# UDP DatagramPacket - Quick Reference Card

## ✅ CORRECT Pattern (Use This!)

```java
// Pattern 1: Direct byte array with exact length
byte[] data = encodeMessage(message);
DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
socket.send(packet);
```

```java
// Pattern 2: Buffer with tracked length
byte[] buffer = new byte[1024];
int actualLength = writeData(buffer);
DatagramPacket packet = new DatagramPacket(buffer, actualLength, address, port);
socket.send(packet);
```

```java
// Pattern 3: ByteBuffer with position tracking
ByteBuffer buf = ByteBuffer.allocate(estimatedSize);
// ... write data ...
byte[] data = new byte[buf.position()]; // Use position()!
buf.flip();
buf.get(data);
DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
```

## ❌ WRONG Patterns (Never Do This!)

```java
// WRONG: Using buffer capacity instead of actual data length
byte[] buffer = new byte[MAX_SIZE];
writeData(buffer); // Only writes 100 bytes
DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port); // Sends all MAX_SIZE bytes!
```

```java
// WRONG: Returning entire array from over-allocated ByteBuffer
ByteBuffer buf = ByteBuffer.allocate(1024);
buf.put(smallData); // Only 50 bytes
return buf.array(); // Returns 1024 bytes including padding!
```

```java
// WRONG: Reusing buffer without clearing
static byte[] sharedBuffer = new byte[1024];
// ... first use: write 200 bytes ...
// ... second use: write 50 bytes ...
send(sharedBuffer); // Sends old data from first use!
```

## 🔍 Debugging

```java
// Log packet details
log.debug("UDP send: buffer={}, length={}, actual={}",
    packet.getData().length,
    packet.getLength(),
    data.length);

// Verify no trailing zeros
assert packet.getLength() == expectedLength : "Length mismatch!";
```

## 📋 Checklist

Before sending UDP packets:
- [ ] Using `data.length` not `buffer.length`?
- [ ] Tracking actual written bytes if using pre-allocated buffer?
- [ ] Using `ByteBuffer.position()` if serializing to ByteBuffer?
- [ ] No buffer reuse without proper clearing?
- [ ] Verified with packet capture (Wireshark)?

## 🎯 Key Takeaway

**ALWAYS use the EXACT length of actual data, NEVER the buffer capacity!**

```java
new DatagramPacket(data, data.length, addr, port) ✅
new DatagramPacket(buffer, actualLength, addr, port) ✅
new DatagramPacket(buffer, buffer.length, addr, port) ⚠️ Only if fully filled!
```

