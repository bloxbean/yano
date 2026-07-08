# ADR-003: Transport Layer — CIP-0137-Inspired App Message Protocols

## Status
Accepted

## Date
2026-03-12

## Context

M0 (ADR-002, multi-peer foundation) is complete — PeerPool, HeaderFanIn, BodyFetchScheduler, and PeerDiscoveryService are implemented and tested. The next step toward the Yaci Application-Specific L2 Framework (ADR-001) is M1: a transport layer that enables Yaci nodes to gossip application-layer messages to each other.

CIP-0137 (Decentralized Message Queue) defines three mini-protocols for decentralized message diffusion across Cardano's P2P network. It was designed for Mithril signature diffusion but is explicitly generic. We adopt CIP-0137's protocol shape (pull-based gossip, topic isolation, TTL, deduplication) but generalize the message envelope to support pluggable authentication instead of KES-only.

### CIP-0137 Summary

CIP-0137 defines three protocols:

1. **Message Submission (N2N)** — Pull-based gossip between nodes. Structurally identical to `TxSubmission`: the inbound side drives data flow by requesting message IDs and then message bodies. Per-peer FIFO queue with ack/req window tracking.

2. **Local Message Submission (N2C)** — Simple request-response for a local application to submit a message to the node for diffusion. Mirrors `LocalTxSubmission`.

3. **Local Message Notification (N2C)** — Blocking or non-blocking consumption for a local application to receive messages from the node. Supports both polling and long-poll patterns.

CIP-0137's message envelope: `[messagePayload, kesSignature, operationalCertificate, coldVerificationKey]` — tightly coupled to SPO KES authentication.

### Where We Diverge from CIP-0137

| Aspect | CIP-0137 | Yaci M1 |
|--------|----------|---------|
| **Authentication** | KES signatures + operational certificates (SPO-only) | Pluggable: Open, Permissioned (M1); SPO-KES, Delegated (M4) |
| **Topic isolation** | Separate network magic per topic (one DMQ node per topic) | `topicId` field in message envelope (single connection serves multiple topics) |
| **Message envelope** | `[payload, kesSignature, opcert, coldVk]` | `[messageId, messageBody, authMethod, authProof, topicId, expiresAt]` |
| **Protocol IDs** | Not specified (implementation-defined) | 100 (N2N), 101 (N2C submit), 102 (N2C notification) |
| **Deployment** | Standalone DMQ node process alongside `cardano-node` | Integrated into Yaci node (app-layer agents on same connection as L1 agents) |

**CIP-0137 compatibility path**: In SPO-KES mode (authMethod=2, planned for M4), the authentication fields can be mapped to CIP-0137's format, enabling future interoperability with CIP-0137 DMQ nodes.

## Decision

### Message Envelope

Opaque payload with structured routing envelope:

```
AppMessage = [
    messageId   : bstr,     -- 32-byte SHA-256 hash (content-addressed)
    messageBody : bstr,     -- opaque app-specific payload
    authMethod  : uint,     -- 0=open, 1=permissioned, 2=spo-kes, 3=delegated
    authProof   : bstr,     -- method-specific authentication proof
    topicId     : tstr,     -- topic identifier for routing
    expiresAt   : uint      -- POSIX timestamp for TTL
]
```

`messageBody` is opaque bytes — application-specific. `topicId` and `expiresAt` are in the outer envelope so nodes can route and expire messages without parsing the payload.

### Protocol 100: App Message Submission (N2N Gossip)

Pull-based gossip, structurally identical to TxSubmission. The outbound side (client in Yaci terms) holds messages and responds when the inbound side (server) requests them.

**State Machine:**

```
                    ┌─────────────────────────────────┐
                    │                                 │
                    v                                 │
  Init ──[MsgInit]──> Idle ──[MsgRequestMessageIds]──>│
                      │  │                            │
                      │  │   ┌── MessageIdsBlocking ──┘
                      │  │   │   (MsgReplyMessageIds)
                      │  │   │
                      │  └───┤── MessageIdsNonBlocking
                      │      │   (MsgReplyMessageIds)
                      │      │
                      │      └── Messages ────────────┘
                      │          (MsgReplyMessages)
                      │
                      └──[MsgDone]──> Done
```

| State | Agency | Description |
|-------|--------|-------------|
| Init | Client (`isClient=true`) | Client sends `MsgInit` to start protocol |
| Idle | Server (`isClient=false`) | Server sends `MsgRequestMessageIds` or `MsgRequestMessages` |
| MessageIdsBlocking | Client | Client replies with available message IDs (blocks until available) |
| MessageIdsNonBlocking | Client | Client replies with available message IDs (returns immediately) |
| Messages | Client | Client replies with full message bodies |
| Done | Nobody | Terminal state |

**CBOR Messages:**

| Tag | Message | Fields | Sender |
|-----|---------|--------|--------|
| 0 | `MsgInit` | — | Client |
| 1 | `MsgRequestMessageIds` | `isBlocking: bool, ack: uint16, req: uint16` | Server |
| 2 | `MsgReplyMessageIds` | `[* [messageId: bstr, size: uint32]]` | Client |
| 3 | `MsgRequestMessages` | `[* messageId: bstr]` | Server |
| 4 | `MsgReplyMessages` | `[* appMessage]` | Client |
| 5 | `MsgDone` | — | Client |

**FIFO Queue Management** (per CIP-0137):
- Outbound side maintains a per-peer FIFO queue of message IDs
- `ack` in `MsgRequestMessageIds` acknowledges N previously-sent IDs (removed from both queues)
- `req` requests N new message IDs
- Blocking request used when buffer is empty; non-blocking when IDs need acknowledging

### Protocol 101: Local App Message Submission (N2C)

Simple request-response for local applications to submit messages.

```
Idle ──[MsgSubmitMessage]──> Busy ──[MsgAcceptMessage]──> Idle
                                  ──[MsgRejectMessage]──> Idle
Idle ──[MsgDone]──> Done
```

| Tag | Message | Fields |
|-----|---------|--------|
| 0 | `MsgSubmitMessage` | `appMessage` |
| 1 | `MsgAcceptMessage` | — |
| 2 | `MsgRejectMessage` | `reason: RejectReason` |
| 3 | `MsgDone` | — |

**RejectReason**: `INVALID(0, tstr)`, `ALREADY_RECEIVED(1)`, `EXPIRED(2)`, `OTHER(3, tstr)`

### Protocol 102: Local App Message Notification (N2C)

Blocking/non-blocking message consumption for local applications.

```
Idle ──[MsgRequestMessages(blocking=false)]──> BusyNonBlocking
Idle ──[MsgRequestMessages(blocking=true)]───> BusyBlocking
BusyNonBlocking ──[MsgReplyMessagesNonBlocking]──> Idle
BusyBlocking ──[MsgReplyMessagesBlocking]──> Idle
Idle ──[MsgClientDone]──> Done
```

| Tag | Message | Fields |
|-----|---------|--------|
| 0 | `MsgRequestMessages` | `isBlocking: bool` |
| 1 | `MsgReplyMessagesNonBlocking` | `messages: [* appMessage], hasMore: bool` |
| 2 | `MsgReplyMessagesBlocking` | `messages: [+ appMessage]` (at least one) |
| 3 | `MsgClientDone` | — |

### Pluggable Authentication

```java
public interface MessageAuthenticator {
    boolean verify(AppMessage message);
    AuthProof sign(byte[] messagePayload);
    int authMethod();
}
```

**M1 Implementations:**

| Mode | Class | Signing | Verification |
|------|-------|---------|-------------|
| 0: Open | `OpenAuthenticator` | Self-generated Ed25519 keypair | Any valid Ed25519 signature accepted |
| 1: Permissioned | `PermissionedAuthenticator` | Node's configured key | Signature valid AND public key in configured allow-list |

**M4 Implementations** (deferred):

| Mode | Class | Description |
|------|-------|-------------|
| 2: SPO-KES | `SpoKesAuthenticator` | KES signature + opcert verified against on-chain stake distribution (CIP-0137 compatible) |
| 3: Delegated | `DelegatedAuthenticator` | SPO-signed delegation cert + delegate signature |

### NodeServerSession Extensibility

New `AgentFactory` functional interface enables plugins to register additional protocol agents per server session:

```java
@FunctionalInterface
public interface AgentFactory {
    Agent<?> createAgent(Channel clientChannel);
}
```

`NodeServer` accepts an optional `List<AgentFactory>`. `NodeServerSession.createAgents()` appends factory-created agents after the existing 4 L1 agents. Existing constructors remain unchanged — fully backward compatible.

### App Message MemPool

Topic-aware message buffer with deduplication and TTL eviction:

```java
public interface AppMessageMemPool {
    boolean addMessage(AppMessage message);
    List<AppMessage> getMessagesForTopic(String topicId, int maxCount);
    boolean contains(byte[] messageId);
    int removeExpired(long currentTime);
    int size();
}
```

Implementation uses `LinkedHashMap<String, AppMessage>` for insertion-order iteration and O(1) lookup. Deduplication by `messageId`. TTL eviction on `expiresAt`.

## Architecture

### Protocol Stack (per connection)

```
┌──────────────────────────────────────────────────────────┐
│                  Yaci-to-Yaci Connection                  │
│                                                          │
│  L1 Protocols (existing)     App Protocols (new, M1)     │
│  ┌─────────────────────┐    ┌──────────────────────────┐│
│  │ 0: Handshake        │    │ 100: AppMsgSubmission    ││
│  │ 2: ChainSync        │    │      (N2N gossip)        ││
│  │ 3: BlockFetch       │    │ 101: LocalAppMsgSubmit   ││
│  │ 4: TxSubmission     │    │      (N2C submit)        ││
│  │ 8: KeepAlive        │    │ 102: LocalAppMsgNotify   ││
│  │ 10: PeerSharing     │    │      (N2C notification)  ││
│  └─────────────────────┘    └──────────────────────────┘│
│                                                          │
│         Netty Pipeline (protocol ID routing)             │
│  MiniProtoStreamingByteToMessageDecoder → Agent[].match  │
└──────────────────────────────────────────────────────────┘
```

### Data Flow

```
Local App                  Yaci Node A              Yaci Node B
   │                            │                         │
   │ POST /api/app/submit       │                         │
   │ (or N2C Protocol 101)      │                         │
   │───────────────────────────►│                         │
   │                            │                         │
   │                  ┌─────────┤                         │
   │                  │ Authenticate                      │
   │                  │ (MessageAuthenticator)             │
   │                  │ Add to AppMessageMemPool           │
   │                  │ Publish AppMessageReceivedEvent    │
   │                  └─────────┤                         │
   │                            │                         │
   │                            │◄── MsgRequestMessageIds │
   │                            │    (Protocol 100)       │
   │                            │                         │
   │                            │──► MsgReplyMessageIds   │
   │                            │    [id1, size1]         │
   │                            │                         │
   │                            │◄── MsgRequestMessages   │
   │                            │    [id1]                │
   │                            │                         │
   │                            │──► MsgReplyMessages     │
   │                            │    [fullMessage]        │
   │                            │                         │
   │                            │    Node B: authenticate,│
   │                            │    add to mempool,      │
   │                            │    publish event        │
   │                            │                         │
   │                  ┌─────────┤                         │
   │                  │ N2C Protocol 102 (optional)       │
   │                  │ Local consumer polls for          │
   │                  │ finalized messages                │
   │                  └─────────┤                         │
   │                            │                         │
   │ 200 OK                     │                         │
   │◄───────────────────────────│                         │
```

## Configuration

```yaml
yaci:
  node:
    # App layer (opt-in, disabled by default)
    app-layer:
      enabled: false
      auth-mode: open           # open | permissioned
      allowed-keys: []          # Hex public keys for permissioned mode
      mempool-max-size: 1000
      default-ttl-seconds: 600  # 10 minutes
```

Backward compatible: when `app-layer.enabled` is false (default), no app-layer agents are created. L1-only behavior is identical to current.

## Implementation

### Package Structure

```
core/src/main/java/.../protocol/appmsg/
  model/
    AppMessage.java, AppMessageId.java, AuthMethod.java
  n2n/
    AppMsgSubmissionState.java, AppMsgSubmissionStateBase.java
    AppMsgSubmissionAgent.java, AppMsgSubmissionServerAgent.java
    AppMsgSubmissionListener.java, AppMsgSubmissionConfig.java
    messages/
      MsgInit, MsgRequestMessageIds, MsgReplyMessageIds,
      MsgRequestMessages, MsgReplyMessages, MsgDone
    serializers/
      AppMsgSubmissionSerializers.java
  n2c/
    submit/
      LocalAppMsgSubmitState.java, LocalAppMsgSubmitStateBase.java
      LocalAppMsgSubmitAgent.java, LocalAppMsgSubmitListener.java
      messages/ + serializers/
    notify/
      LocalAppMsgNotifyState.java, LocalAppMsgNotifyStateBase.java
      LocalAppMsgNotifyAgent.java, LocalAppMsgNotifyListener.java
      messages/ + serializers/

core/src/main/java/.../network/server/
  AgentFactory.java  (new)

node-api/src/main/java/.../appmsg/
  MessageAuthenticator.java, AuthProof.java, AppMessageListener.java
node-api/src/main/java/.../events/
  AppMessageReceivedEvent.java, AppMessagePropagatedEvent.java

node-runtime/src/main/java/.../appmsg/
  AppMessageMemPool.java, DefaultAppMessageMemPool.java
  YaciAppMessageHandler.java
  auth/
    OpenAuthenticator.java, PermissionedAuthenticator.java
```

### Prerequisite Fix

`core/protocol/Agent.java` line 97: protocol ID range check `baseProtocolId > 100` blocks app-layer protocols. Change upper bound to `199`.

### Key Reusable Code

| Existing | Reuse For |
|----------|-----------|
| `TxSubmissionServerAgent` | Template for `AppMsgSubmissionServerAgent` (FIFO queue, ack/req, pending request pattern) |
| `TxSubmissionAgent` | Template for `AppMsgSubmissionAgent` (client-side queue management) |
| `TxSubmissionState` | Template for `AppMsgSubmissionState` (identical state machine) |
| `TxSubmissionMessagesSerializers` | Template for CBOR serialization pattern (enum singletons, tag-based dispatch) |
| `LocalTxSubmissionAgent` | Template for Protocol 101 agent |
| `DefaultMemPool` | Template for `DefaultAppMessageMemPool` |
| `YaciTxSubmissionHandler` | Template for `YaciAppMessageHandler` (mempool + events wiring) |

### Modifications to Existing Code

| File | Change |
|------|--------|
| `core/.../Agent.java` | Fix protocol ID range check (line 97) |
| `core/.../server/NodeServer.java` | Add constructor overload accepting `List<AgentFactory>` |
| `core/.../server/NodeServerSession.java` | Invoke `AgentFactory` list in `createAgents()` |
| `helper/.../N2NPeerFetcher.java` | Add optional `additionalAgents` parameter for app-layer agents |
| `helper/.../PeerClient.java` | Add constructor/setter for additional agents |
| `node-api/.../config/YaciNodeConfig.java` | Add app-layer config fields |
| `node-runtime/.../YaciNode.java` | Wire app-layer agents when `enableAppLayer` is true |
| `node-app/.../resources/application.yml` | Add app-layer config section |

### Implementation Order

1. **Core models + Protocol 100 state machine** — models, state enum, messages, serializers, Agent.java fix
2. **Protocol 100 agents** — client + server agents, listener, config
3. **Protocol 101 + 102** — N2C submit and notification protocols
4. **node-api interfaces + events** — MessageAuthenticator, events
5. **NodeServerSession extensibility** — AgentFactory, NodeServer/Session changes
6. **node-runtime implementations** — authenticators, mempool, handler
7. **Wiring + configuration** — YaciNodeConfig, YaciNode, PeerClient, application.yml
8. **Integration testing** — two-node gossip end-to-end

## Events

| Event | Type | Published When |
|-------|------|----------------|
| `AppMessageReceivedEvent` | Regular | App message received from peer or local submission and authenticated |
| `AppMessagePropagatedEvent` | Regular | App message sent to N connected peers via Protocol 100 |

## Risks

| Risk | Mitigation |
|------|-----------|
| Protocol isolation — bug in app agent affects L1 sync | Strict protocol isolation: separate message parsing, no shared mutable state between L1 and app agents. Protocol ID routing in Netty handler ensures independence. |
| Large message bodies | Enforce configurable max message size in `AppMsgSubmissionConfig`. Reject oversized messages with `RejectReason.INVALID`. |
| Memory pressure from app mempool | Configurable max size + TTL eviction. Separate from L1 mempool. |
| Backward compatibility | App layer disabled by default. Existing constructors unchanged. AgentFactory is additive. |
| No KES verification in M1 | Acceptable — M1 targets Open and Permissioned modes (enterprise use cases). KES deferred to M4 when Java KES library is available. |

## Relationship to Other ADRs

- **ADR-001** (L2 Framework): This ADR implements M1 of the roadmap — the transport layer for app-layer message gossip.
- **ADR-002** (Multi-Peer Foundation): M0 provides the PeerPool and multi-peer infrastructure that M1 builds on. Yaci-type peers carry both L1 and app-layer protocols on the same connection.
