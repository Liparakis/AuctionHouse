# AuctionHouse

---

## 1. Overview

AuctionHouse is a real-time distributed auction system built on a central server / multi-peer architecture. Peers act as sellers, buyers, or both. The system supports concurrent auctions, live event broadcasting, peer-to-peer file transfer, and reputation-based fallback handling.

**Build environment:**

| Tool | Version |
|---|---|
| JDK | OpenJDK 25 LTS (Temurin-25+36) |
| Build tool | Gradle 9.5.0 |
| Project type | Gradle multi-module (`common`, `server`, `peer`) |

---

## 2. Architecture

### 2.1 Auction Server

The server is the authoritative component. It runs one thread per connected client and protects all shared state with locks and thread-safe data structures.

**Responsibilities:**

- User authentication and session token lifecycle
- Active peer endpoint registry
- Auction queue management with a hard cap of **2 concurrent active auctions**
- Auction selection by `reputation_score` of the front-of-queue sellers
- Bid validation with server-side locking
- Event broadcasting to all connected peers
- Post-auction transaction orchestration and fallback promotion
- Reputation and statistics updates

| Class | Role |
|---|---|
| `ServerMain` | Entry point |
| `AuctionServer` | Lifecycle and socket accept loop |
| `ClientHandler` | Per-client thread |
| `CommandProcessor` | Command dispatch |
| `AuctionEngine` | Auction state machine |
| `ServerState` | Shared mutable state |

### 2.2 Peer

Each peer operates as a client to the Auction Server and as a direct endpoint for peer-to-peer transactions.

**Responsibilities:**

- Registration, login, logout
- Local `shared_directory` and metadata file management
- Auction submission and live tracking
- Bid submission
- Buyer/seller roles in P2P transactions

Two operating modes are supported: `interactive` for manual use and `auto` for scripted multi-peer testing.

### 2.3 Peer-to-Peer Transaction

After auction close, the buyer connects directly to the seller over **UDP** to receive the item's metadata file. Transfer uses **Go-Back-N**:

- Payload segmented into `64-byte` packets
- Window size `N = 3`
- Cumulative ACKs from the buyer
- `2-second` retransmission timeout on the seller side

The buyer simulates link unreliability: incoming packets are dropped with 20% probability. The awarded winner cancels the transaction with 30% probability, triggering a fallback promotion and a reputation penalty.

> Operates correctly on LAN. Public internet requires port forwarding or a VPN — no NAT traversal is implemented.

---

## 3. Protocol

All messages are plain-text, `\n`-terminated, with `|` as the field delimiter. The format is consistent across all message types:

```
COMMAND|arg1|arg2
OK|code|field1|field2
ERR|error_code|message
EVENT|event_type|field1|field2
```

Request–response correlation uses a prefixed ID:

```
requestId#COMMAND|...
requestId#OK|...
requestId#ERR|...
```

Events are not correlated to requests — they arrive asynchronously on the same socket.

### Commands

**Guest (unauthenticated)**

| Command | Description |
|---|---|
| `PING` | Liveness check |
| `HELLO` | Handshake |
| `REGISTER\|username\|password` | Register a new user |
| `LOGIN\|username\|password` | Authenticate |

**Authenticated**

| Command | Description |
|---|---|
| `LOGOUT` | End session |
| `SEND_AUCTION_REQUEST\|description\|startPrice\|durationSec` | Submit an item for auction |
| `SEND_AUCTION_REQUEST\|--meta\|objectId` | Reuse an existing metadata object |
| `GET_CURRENT_AUCTION` | Summary of active auctions |
| `GET_DETAILS\|objectId` | Full details for a specific active object |
| `BID\|objectId\|amount` | Submit a bid |
| `GET_USER_STATS\|[username]` | Retrieve user statistics |
| `PEER_LISTEN\|ip\|port` | Declare the peer's reachable endpoint |
| `TRANSACTION_COMPLETE\|objectId` | Confirm a successful transaction |
| `TRANSACTION_FAILED\|objectId` | Report a failed or cancelled transaction |

### Events

| Event | Description |
|---|---|
| `EVENT\|AUCTION_QUEUED\|...` | Item entered the queue |
| `EVENT\|AUCTION_STARTED\|...` | Auction has begun |
| `EVENT\|BID_ACCEPTED\|...` | Bid accepted |
| `EVENT\|AUCTION_ENDED\|...` | Auction closed (`AWARDED` or `NO_WINNER`) |
| `EVENT\|AUCTION_CANCELLED\|...` | Auction cancelled |
| `EVENT\|TRANSACTION_READY\|...` | Winner must initiate transaction |
| `EVENT\|TRANSACTION_PROMOTED\|...` | Fallback bidder promoted |
| `EVENT\|TRANSACTION_COMPLETED\|...` | Transaction succeeded |
| `EVENT\|TRANSACTION_FAILED\|...` | Transaction failed definitively |

On `AUCTION_ENDED`, the outcome is either `AWARDED` (eligible bidder exists, transaction pending) or `NO_WINNER`. An item is considered sold only after `TRANSACTION_COMPLETED`.

---

## 4. Technical Challenges

### Multiplexed socket — events and responses on the same connection

Both command responses and asynchronous events arrive on the same TCP socket. A peer waiting on a `BID` reply may receive an `EVENT|BID_ACCEPTED` first.

**Solution:** Incoming messages are routed at read time into separate queues — one for correlated responses, one for events — allowing each to be consumed independently.

---

### Bid race conditions

Multiple peers bidding concurrently could each read the same stale current price before any write committed.

**Solution:** Every bid acceptance is an atomic state update under a server-side lock. No bid is evaluated against an intermediate state.

---

### Late bids at auction close

A race condition existed between auction timer expiry and a bid arriving in the same instant.

**Solution:** Remaining auction time is re-checked inside the lock before any bid is accepted, ensuring no bid is processed after the deadline.

---

### Session identity after reconnect

A peer could disconnect and reconnect, but the fallback and transaction logic still held a reference to the old session token — causing a valid user to be incorrectly classified as ineligible.

**Solution:** Eligibility checks were decoupled from session tokens and bound to the canonical user identity, eliminating the stale-token inconsistency.

---

### Premature `SOLD` state

The system originally marked an item as `SOLD` immediately on auction close, before the transaction had completed.

**Solution:** The close state is now `AWARDED` (transaction pending) or `NO_WINNER`. `SOLD` is only implied by a subsequent `TRANSACTION_COMPLETED`.

---

### Seller disconnection during active auction

A seller disconnecting mid-auction left the auction in an undefined state.

**Solution:** Session teardown removes the seller's endpoint and immediately broadcasts `EVENT|AUCTION_CANCELLED` to all peers.

---

### Advertised IP defaulting to loopback

In multi-machine tests, peers advertising `127.0.0.1` made P2P transactions unreachable.

**Solution:** Peers support an explicit advertised IP at `PEER_LISTEN` time; the server uses the declared address for all P2P routing.

---

### Metadata duplication on `--meta`

Reusing an existing object via `--meta` could inadvertently create a second metadata file for the same object.

**Solution:** The flow checks for an existing file first and reuses it, making the operation idempotent.

---

### Metadata lifecycle across transaction parties

The metadata file needed to be present on the buyer's side after transfer and cleanly removed from the seller's after confirmation.

**Solution:** A dedicated post-transfer handshake — `TRANSACTION_COMPLETE` to the server — gates the cleanup, ensuring both sides are consistent before any removal occurs.

---

### Console output collision in interactive mode

Live auction event updates and user input could interleave in the terminal, corrupting both.

**Solution:** A dedicated live tracker handles display independently of the input loop.

---

## 5. Known Security Gaps

> This system is designed for academic use in a controlled environment. The gaps below are acknowledged and intentionally out of scope. The system is **not production-ready**.

**Transport:** No TLS on any channel. Passwords, session tokens, and file metadata are transmitted in plaintext.

**Authentication & session management:**

| # | Gap |
|---|---|
| 1 | No account lockout or brute-force protection |
| 2 | No replay attack protection |
| 3 | No HMAC or message authentication codes |
| 4 | No session token theft protection |
| 5 | No username enumeration protection |
| 6 | No password strength policy or token rotation |

**Peer identity & P2P:**

| # | Gap |
|---|---|
| 7 | No cryptographic peer identity verification |
| 8 | Advertised IP is not validated against the actual source address |
| 9 | Token leakage enables spoofed P2P connections |

**Authorization & audit:**

| # | Gap |
|---|---|
| 10 | No role-based authorization beyond session context |
| 11 | No persistent audit log or tamper-evident event trail |

---

## 6. Build & Execution

```powershell
# Build
.\gradlew.bat build

# Stage binaries
.\gradlew.bat stageBin

# Start server
bin\run-server.bat

# Start peer
bin\run-peer.bat
```

Shared object files are stored per-user under:

```
bin/shared_objects/<username>/
```