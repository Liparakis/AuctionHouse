# AuctionHouse Report

## 1. Summary

This project implements a distributed auction platform with:

- a central Auction Server over TCP
- multiple peers that act as sellers and bidders
- up to 2 simultaneous live auctions
- reputation-aware auction scheduling
- peer-to-peer transaction completion over UDP
- Go-Back-N reliable transfer for metadata files over UDP

The codebase is a Gradle multi-module project:

- `common`: shared models, protocol, enums
- `server`: Auction Server, command handling, auction engine
- `peer`: interactive/auto peer logic, live tracking, transaction logic

## 2. High-Level Architecture

### 2.1 Server

The Auction Server is responsible for:

- registering and authenticating users
- storing per-user stats:
  - `num_auctions_seller`
  - `num_auctions_bidder`
  - `reputation_score`
- keeping a queue of pending auction requests
- keeping up to 2 active auctions at the same time
- selecting the next auction by comparing the reputation of the first two queued sellers
- validating bids according to the assignment formulas
- ending auctions and triggering transactions
- handling fallback assignment when the highest bidder cancels

### 2.2 Peer

Each peer acts as:

- TCP client of the Auction Server
- UDP server for incoming metadata transfers when it is a seller
- UDP client when it is a buyer and must complete a transaction

Two peer modes exist:

- `interactive`: manual commands
- `auto`: automated bidding and auction submission for testing

## 3. Auction Logic

### 3.1 Simultaneous auctions

The server supports at most 2 active auctions at any time.

When a slot becomes free:

1. the server checks the first 2 items in the queue
2. it compares the sellers' `reputation_score`
3. it starts the item whose seller has higher reputation

### 3.2 Bidding

Bids are object-scoped:

- `BID|objectId|amount`

The server validates all bids server-side.

Rules:

- normal phase:
  - `NewBid = HighestBid * (1 + RAND/10)`
  - increase up to 10%
- last 10% of auction duration:
  - increase up to 20%

The current implementation enforces:

- bid must be strictly greater than current highest bid
- bid must not exceed the maximum allowed random-window cap for the current phase
- seller cannot bid on its own auction

### 3.3 End of auction

When an auction expires:

- the highest bid is selected
- the auction is moved to pending transaction state
- the server broadcasts transaction readiness

If the top bidder cancels:

- reputation is decreased
- the server promotes the next eligible connected bidder
- if nobody is left, the transaction fails permanently

## 4. Reputation

Each user starts with:

- `reputation_score = 1.0`

Update formula:

`new_reputation = (1 - beta) * old_reputation + beta * outcome`

Where:

- `beta = 0.25`
- `outcome = 1` on successful purchase
- `outcome = 0` on cancellation/failure by awarded bidder

## 5. Transaction over UDP

After auction completion, the buyer contacts the seller peer-to-peer over UDP.

### 5.1 Go-Back-N characteristics

- UDP transport
- payload split into 64-byte chunks
- sender window size `N = 3`
- sequence numbers in packets
- cumulative ACKs from buyer
- timeout `2 sec`
- on timeout, seller retransmits from oldest unacknowledged packet

### 5.2 Unreliable network simulation

Buyer behavior:

- drops incoming data packets with probability 20%
- sends a needed new ACK with probability 80%

### 5.3 Success path

On success:

- buyer reconstructs the metadata file
- buyer stores it in its `shared_directory`
- seller deletes the original metadata file
- buyer informs the server
- server updates bidder reputation and counters

### 5.4 Cancellation path

The awarded bidder proceeds with probability 70% and cancels with probability 30%.

On cancellation:

- buyer informs the server of transaction failure
- server lowers reputation
- server promotes the next highest connected bidder

## 6. Important Source Files

### Shared

- `common/src/main/java/gr/aueb/auctionhouse/common/model/CurrentAuction.java`
- `common/src/main/java/gr/aueb/auctionhouse/common/protocol/builder/CommandWire.java`
- `common/src/main/java/gr/aueb/auctionhouse/common/protocol/enums/CommandType.java`
- `common/src/main/java/gr/aueb/auctionhouse/common/protocol/enums/EventType.java`
- `common/src/main/java/gr/aueb/auctionhouse/common/protocol/enums/OkCode.java`

### Server

- `server/src/main/java/gr/aueb/auctionhouse/server/state/ServerState.java`
- `server/src/main/java/gr/aueb/auctionhouse/server/auction/AuctionEngine.java`
- `server/src/main/java/gr/aueb/auctionhouse/server/client/CommandProcessor.java`
- `server/src/main/java/gr/aueb/auctionhouse/server/client/ClientHandler.java`

### Peer

- `peer/src/main/java/gr/aueb/auctionhouse/peer/core/AuctionAutoSession.java`
- `peer/src/main/java/gr/aueb/auctionhouse/peer/core/AuctionLiveTracker.java`
- `peer/src/main/java/gr/aueb/auctionhouse/peer/core/utils/PeerTransactionHandler.java`
- `peer/src/main/java/gr/aueb/auctionhouse/peer/transaction/PeerTransactionClient.java`
- `peer/src/main/java/gr/aueb/auctionhouse/peer/transaction/PeerTransactionServer.java`
- `peer/src/main/java/gr/aueb/auctionhouse/peer/transaction/ObjectMetadataStore.java`

## 7. Commands and Protocol

### Guest commands

- `PING`
- `HELLO`
- `REGISTER|username|password`
- `LOGIN|username|password`

### Authenticated commands

- `LOGOUT`
- `SEND_AUCTION_REQUEST|description|startPrice|durationSec`
- `PEER_LISTEN|ip|port`
- `GET_CURRENT_AUCTION`
- `GET_DETAILS|objectId`
- `BID|objectId|amount`
- `GET_USER_STATS|[username]`
- `TRANSACTION_COMPLETE|objectId`
- `TRANSACTION_FAILED|objectId`

### Main server events

- `AUCTION_QUEUED`
- `AUCTION_STARTED`
- `BID_ACCEPTED`
- `AUCTION_ENDED`
- `AUCTION_CANCELLED`
- `TRANSACTION_READY`
- `TRANSACTION_PROMOTED`
- `TRANSACTION_COMPLETED`
- `TRANSACTION_FAILED`

## 8. Build and Run

### Build

```powershell
.\gradlew.bat build
```

### Create runnable jars and scripts

```powershell
.\gradlew.bat stageBin
```

### Run server

```powershell
bin\run-server.bat
```

### Run peer

```powershell
bin\run-peer.bat
```

### Shared object directory

```text
bin/shared_objects/<peer-name>/
```

## 9. Tests and Verification

Automated tests added:

- `common/src/test/java/gr/aueb/auctionhouse/common/model/CurrentAuctionTest.java`
- `common/src/test/java/gr/aueb/auctionhouse/common/protocol/builder/CommandWireTest.java`
- `server/src/test/java/gr/aueb/auctionhouse/server/state/ServerStateTest.java`

Verified with:

```powershell
.\gradlew.bat build
.\gradlew.bat stageBin
```

Both commands completed successfully.

## 10. End-to-End Evidence

A validated end-to-end automated run was captured under:

- `bin/logs/e2e/20260524-064413/`

Useful files:

- `server.out.log`
- `peer-sellerA.err.log`
- `peer-sellerB.err.log`
- `peer-buyerA.err.log`
- `peer-buyerB.err.log`

This run demonstrates:

- 2 simultaneous auctions started
- bidding on both auctions
- bidder cancellation
- fallback promotion to next bidder
- successful UDP metadata transfer
- successful transaction completion after promotion

Examples from the server log:

- `startAuction object=854409d9-...`
- `startAuction object=85a64b09-...`
- `transactionFailed object=854409d9-... bidder=...`
- `transactionPromoted object=854409d9-... nextBidder=...`
- `transactionComplete object=854409d9-... bidder=...`

Examples from peer logs:

- `TXN cancelled by winner object=...`
- `Completed UDP transaction - object=...`

## 11. Known Limitations

- The report screenshots folder still contains older screenshots from the previous version and should be refreshed for final submission.
- The auto peers are useful for stress/testing, but they generate aggressive bid traffic and are not meant to represent realistic user behavior.
- The server resolves transaction success/failure from peer notifications and log evidence; it is not a durable database-backed system.

## 12. Conclusion

The final implementation now matches the updated assignment direction:

- 2 live auctions
- bid growth rule with 10% and late 20% phases
- UDP transaction with Go-Back-N
- probabilistic buyer cancellation
- reputation-based fallback and scheduling

