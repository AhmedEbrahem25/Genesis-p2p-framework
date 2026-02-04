# Project Issue Report: P2P Discovery & Port Connectivity

## 1. Project Overview

This project implements a **peer-to-peer (P2P) communication system** with the following networking components:

* **TCP 8081** – Main P2P communication channel
* **UDP 8080** – Transport layer (encrypted)
* **UDP 5000** – Multicast-based peer discovery (`239.255.0.1`)
* **UDP 5001** – Broadcast-based peer discovery

The system is deployed on **Windows host and Windows VM (VMware)**.

---

## 2. Reported Issues

### Issue 1: UDP Discovery Ports Not Appearing in netstat

**Observation:**

```cmd
netstat -an | findstr UDP | findstr :5000
netstat -an | findstr UDP | findstr :5001
```

Returned **no output**.

**Impact:**

* Developers assumed ports were closed or blocked.
* Discovery appeared non-functional.

**Root Cause:**

* UDP ports do **not appear in netstat unless the application explicitly binds** to them.
* Sending-only UDP sockets do not create a visible listening state.

---

### Issue 2: Multicast Group Not Joined

**Observation:**

```cmd
netsh interface ipv4 show joins
```

Output did **not** include:

```
239.255.0.1
```

**Impact:**

* Multicast discovery packets were never received.
* No peers discovered across host/VM.

**Root Cause:**

* Application did not successfully call multicast join (`IP_ADD_MEMBERSHIP`).
* Or multicast join occurred on the wrong network interface.

---

### Issue 3: Misunderstanding Firewall vs Port Opening

**Observation:**

* Firewall rules were added correctly.
* Developers expected this to "open" multicast/broadcast ports.

**Impact:**

* Time spent debugging firewall instead of socket logic.

**Root Cause:**

* Firewall rules **do not create sockets**.
* Multicast and broadcast ports only exist while an application socket is bound and active.

---

### Issue 4: VM Networking Constraints

**Observation:**

* VMware adapters present: `VMnet1 (Host-only)` and `VMnet8 (NAT)`.

**Impact:**

* Discovery traffic inconsistent or missing.

**Root Cause:**

* NAT (`VMnet8`) does **not reliably support multicast/broadcast**.
* Correct modes are **Host-only (VMnet1)** or **Bridged**.

---

## 3. Verified Working Components

| Component | Status       | Notes                      |
| --------- | ------------ | -------------------------- |
| TCP 8081  | Working      | Appears as LISTENING       |
| UDP 8080  | Working      | Bound internally           |
| UDP 5000  | ❌ Not joined | Multicast group missing    |
| UDP 5001  | ❌ Not bound  | Broadcast listener missing |

---

## 4. Required Fixes (Action Items for Coders)

### 4.1 Multicast Discovery (UDP 5000)

The application **must**:

1. Bind a UDP socket to `0.0.0.0:5000`
2. Join multicast group `239.255.0.1`
3. Explicitly select the correct interface (VMnet1 or Bridged)

**Verification:**

```cmd
netsh interface ipv4 show joins
```

Expected:

```
239.255.0.1
```

---

### 4.2 Broadcast Discovery (UDP 5001)

The application **must**:

1. Bind a UDP socket to `0.0.0.0:5001`
2. Enable broadcast (`SO_BROADCAST = true`)

**Verification:**

```cmd
netstat -an | findstr UDP | findstr :5001
```

Expected:

```
UDP    0.0.0.0:5001    *:*
```

---

### 4.3 Firewall Configuration (Confirmed Correct)

Firewall should remain **ENABLED** with explicit rules:

* TCP 8081 (Inbound)
* UDP 8080 (Inbound)
* UDP 5000 (Inbound – Multicast)
* UDP 5001 (Inbound – Broadcast)

⚠️ Firewall changes alone are insufficient without correct socket logic.

---

## 5. Manual Validation Method (Used During Debugging)

PowerShell was used to manually:

* Bind UDP ports
* Join multicast groups
* Confirm OS/network behavior

This confirmed:

* OS and firewall are functioning correctly
* Issue is **application-level socket implementation**

---

## 6. Final Conclusion

> The discovery failure is caused by missing UDP socket binding and multicast group joining in the application code, not by Windows Firewall or port configuration. Multicast and broadcast ports cannot be opened manually and only exist while a socket is bound.

---

## 7. Recommendation

* Update discovery module to explicitly bind and join multicast/broadcast sockets.
* Log successful multicast joins at startup.
* Add startup self-checks for discovery readiness.

---

**Prepared for:** Development Team
**Purpose:** Debugging, Fix Implementation, Documentation