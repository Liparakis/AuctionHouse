package gr.aueb.auctionhouse.server.client;

import gr.aueb.auctionhouse.common.model.AuctionRequest;
import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.message.CommandMessage;
import gr.aueb.auctionhouse.server.audit.TokenAuditEvent;
import gr.aueb.auctionhouse.server.audit.TokenAuditLogger;
import gr.aueb.auctionhouse.server.auction.AuctionEngine;
import gr.aueb.auctionhouse.server.config.ServerHelpCatalog;
import gr.aueb.auctionhouse.server.config.ServerMessages;
import gr.aueb.auctionhouse.server.config.ServerValidation;
import gr.aueb.auctionhouse.server.security.PasswordHasher;
import gr.aueb.auctionhouse.server.state.ServerState;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CommandProcessor {

  private static final String DEFAULT_ITEM_DESCRIPTION = "Some item";

  private final ServerState state;
  private final AuctionEngine auctionEngine;

  public CommandProcessor(ServerState state, AuctionEngine auctionEngine) {
    this.state = state;
    this.auctionEngine = auctionEngine;
  }

  public String process(ClientSession session, CommandMessage message) {
    return switch (message.command()) {
      case PING -> Protocol.ok(OkCode.PONG);
      case HELLO -> Protocol.ok(OkCode.WELCOME, ServerMessages.WELCOME_BANNER);
      case REGISTER -> handleRegister(message);
      case LOGIN -> handleLogin(session, message);
      case LOGOUT -> handleLogout(session);
      case SEND_AUCTION_REQUEST -> getAuctionRequest(session, message);
      case PEER_LISTEN -> handlePeerListen(session, message);
      case GET_CURRENT_AUCTION -> sendCurrentAuctions();
      case GET_DETAILS -> sendAuctionDetails(message);
      case BID -> placeBid(session, message);
      case GET_USER_STATS -> handleGetUserStats(session, message);
      case TRANSACTION_COMPLETE -> handleTransactionComplete(session, message);
      case TRANSACTION_FAILED -> handleTransactionFailed(session, message);
      case HELP -> handleHelp(session);
      case UNKNOWN ->
          Protocol.error(ErrorCode.BAD_COMMAND, "Unknown command: " + message.rawCommand());
    };
  }

  private String handleRegister(CommandMessage message) {
    if (message.args().length != 2) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_REGISTER_REQUIRES_2_ARGS);
    }
    String username = normalizeUsername(message.args()[0]);
    String password = message.args()[1];

    state.userSessionLock().lock();
    try {
      if (state.users().containsKey(username)) {
        System.out.println("[SERVER] REGISTER failed user=" + username + " reason=user_exists");
        return Protocol.error(ErrorCode.USER_EXISTS, ServerMessages.MSG_USERNAME_EXISTS);
      }
      state.users().put(username, PasswordHasher.hash(password));
      state.userStats().putIfAbsent(username, ServerState.UserStats.initial());
      System.out.println("[SERVER] REGISTER ok user=" + username);
      return Protocol.ok(OkCode.REGISTERED, username);
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private String handleLogin(ClientSession session, CommandMessage message) {
    if (message.args().length != 2) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_LOGIN_REQUIRES_2_ARGS);
    }
    String username = normalizeUsername(message.args()[0]);
    String password = message.args()[1];

    state.userSessionLock().lock();
    try {
      String error = validateLoginPreconditions(session, username, password);
      if (error != null) {
        return error;
      }

      return mintSessionToken(session, username);
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private String validateLoginPreconditions(ClientSession session, String username,
      String password) {
    String currentToken = session.token();
    if (currentToken != null && state.sessions().containsKey(currentToken)) {
      return Protocol.error(ErrorCode.AUTH_FAILED,
          ServerMessages.MSG_ALREADY_LOGGED_IN_THIS_CLIENT);
    }
    String savedHash = state.users().get(username);
    if (!PasswordHasher.verify(password, savedHash)) {
      return Protocol.error(ErrorCode.AUTH_FAILED, ServerMessages.MSG_INVALID_CREDENTIALS);
    }
    if (isUsernameAlreadyLoggedIn(username)) {
      return Protocol.error(ErrorCode.AUTH_FAILED, ServerMessages.MSG_ACCOUNT_ALREADY_LOGGED_IN);
    }
    return null;
  }

  private String mintSessionToken(ClientSession session, String username) {
    String token = UUID.randomUUID().toString();
    long expiresAt = System.currentTimeMillis() + ServerValidation.SESSION_TOKEN_TTL_MS;
    state.sessions().put(token, new ServerState.SessionInfo(username, expiresAt));
    session.setToken(token);
    TokenAuditLogger.log(TokenAuditEvent.TOKEN_ISSUED, username, token,
        session.remoteAddress(), ServerMessages.DETAIL_LOGIN_SUCCESS);
    return Protocol.ok(OkCode.TOKEN, token);
  }

  private boolean isUsernameAlreadyLoggedIn(String username) {
    long now = System.currentTimeMillis();
    return state.sessions().values().stream()
        .anyMatch(info -> username.equals(info.username()) && !info.isExpired(now));
  }

  private String handleLogout(ClientSession session) {
    String token = session.token();
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_NO_ACTIVE_SESSION);
    }

    state.userSessionLock().lock();
    try {
      ServerState.SessionInfo info = state.sessions().get(token);
      revokeToken(session, token, info == null ? null : info.username(),
          TokenAuditEvent.TOKEN_REVOKED_LOGOUT, ServerMessages.DETAIL_LOGOUT);
      System.out.println("[SERVER] LOGOUT ok user=" + (info == null ? "" : info.username()));
      return Protocol.ok(OkCode.LOGGED_OUT);
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private String getAuctionRequest(ClientSession session, CommandMessage message) {
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }

    int argc = message.args().length;
    if (argc != 2 && argc != 3 && argc != 5) {
      return Protocol.error(ErrorCode.BAD_REQUEST,
          ServerMessages.MSG_SEND_AUCTION_REQUEST_REQUIRES_2_OR_3_ARGS);
    }

    return parseAndEnqueueAuction(session, message, token);
  }

  private String parseAndEnqueueAuction(ClientSession session, CommandMessage message,
      String token) {
    String[] args = message.args();
    String description = DEFAULT_ITEM_DESCRIPTION;
    double startPrice;
    int durationSec;
    ServerState.PeerEndpoint endpoint = state.activePeers().get(token);

    try {
      if (args.length == 2) {
        startPrice = Double.parseDouble(args[0]);
        durationSec = Integer.parseInt(args[1]);
      } else if (args.length == 3) {
        description = blankToDefault(args[0]);
        startPrice = Double.parseDouble(args[1]);
        durationSec = Integer.parseInt(args[2]);
      } else {
        description = blankToDefault(args[0]);
        startPrice = Double.parseDouble(args[1]);
        durationSec = Integer.parseInt(args[2]);

        String error = registerInlinePeerEndpoint(session, token, args[3], args[4]);
        if (error != null) {
          return error;
        }

        endpoint = state.activePeers().get(token);
      }
    } catch (NumberFormatException ex) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_BAD_AUCTION_REQUEST_NUMERIC);
    }

    if (startPrice <= 0 || durationSec <= 0) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_BAD_AUCTION_REQUEST_POSITIVE);
    }
    if (endpoint == null) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_PEER_ENDPOINT_REQUIRED);
    }

    return requestAuction(token, endpoint, description, startPrice, durationSec);
  }

  private String registerInlinePeerEndpoint(ClientSession session, String token, String rawIp,
      String rawPort) {
    int listenPort;
    try {
      listenPort = Integer.parseInt(rawPort);
    } catch (NumberFormatException ex) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_BAD_AUCTION_REQUEST_NUMERIC);
    }
    if (listenPort < ServerValidation.MIN_TCP_PORT || listenPort > ServerValidation.MAX_TCP_PORT) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_PORT_OUT_OF_RANGE);
    }

    String ip = rawIp.isBlank()
        ? session.socket().getInetAddress().getHostAddress()
        : rawIp.trim();

    state.userSessionLock().lock();
    try {
      String username = resolveUsernameForToken(token);
      state.activePeers().put(token, new ServerState.PeerEndpoint(ip, listenPort, username));
    } finally {
      state.userSessionLock().unlock();
    }
    return null;
  }

  private String requestAuction(String token, ServerState.PeerEndpoint endpoint,
      String description, double startPrice, int durationSec) {
    AuctionRequest request = AuctionRequest.newRequest(
        token, endpoint.ip(), endpoint.port(), description, startPrice, durationSec);

    state.auctionStateLock().lock();
    try {
      state.auctionQueue().add(request);
      System.out.println("[SERVER] requestAuction queued object=" + request.objectId()
          + " seller=" + endpoint.username());
      state.broadcast(Protocol.event(
          EventType.AUCTION_QUEUED,
          request.objectId(),
          request.sellerToken(),
          Double.toString(request.startingPrice()),
          Integer.toString(request.durationSeconds())
      ));
      return Protocol.ok(OkCode.AUCTION_ENQUEUED, request.objectId());
    } finally {
      state.auctionStateLock().unlock();
    }
  }

  private String sendCurrentAuctions() {
    List<CurrentAuction> auctions = state.activeAuctionList();
    if (auctions.isEmpty()) {
      return Protocol.ok(OkCode.NO_AUCTION);
    }

    String[] fields = new String[1 + auctions.size() * 5];
    fields[0] = Integer.toString(auctions.size());
    for (int i = 0; i < auctions.size(); i++) {
      CurrentAuction auction = auctions.get(i);
      CurrentAuction.BidSnapshot snapshot = auction.bidSnapshot();
      int base = 1 + (i * 5);
      fields[base] = auction.objectId();
      fields[base + 1] = auction.description();
      fields[base + 2] = Double.toString(snapshot.highestBid());
      fields[base + 3] = Long.toString(auction.remainingSeconds());
      fields[base + 4] = Integer.toString(auction.durationSeconds());
    }
    return Protocol.ok(OkCode.ACTIVE_AUCTIONS, fields);
  }

  private String sendAuctionDetails(CommandMessage message) {
    if (message.args().length != 1 || message.args()[0] == null || message.args()[0].isBlank()) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_GET_DETAILS_REQUIRES_0_ARGS);
    }
    if (state.activeAuctions().isEmpty()) {
      return Protocol.error(ErrorCode.NO_AUCTION, ServerMessages.MSG_NO_ACTIVE_AUCTION);
    }
    CurrentAuction auction = state.activeAuctions().get(message.args()[0].trim());
    if (auction == null) {
      return Protocol.error(ErrorCode.INVALID_OBJECT, "Unknown active object");
    }
    return getAuctionDetails(auction);
  }

  private String getAuctionDetails(CurrentAuction auction) {
    CurrentAuction.BidSnapshot snapshot = auction.bidSnapshot();
    String bidderToken = snapshot.highestBidderToken() == null ? "" : snapshot.highestBidderToken();
    return Protocol.ok(
        OkCode.CURRENT_AUCTION,
        auction.objectId(),
        auction.description(),
        auction.sellerToken(),
        Double.toString(snapshot.highestBid()),
        bidderToken,
        Long.toString(auction.remainingSeconds()),
        Integer.toString(auction.durationSeconds())
    );
  }

  private String placeBid(ClientSession session, CommandMessage message) {
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }
    if (message.args().length != 2) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_BID_REQUIRES_1_ARG);
    }

    String objectId = message.args()[0] == null ? "" : message.args()[0].trim();
    double amount;
    try {
      amount = Double.parseDouble(message.args()[1]);
    } catch (NumberFormatException ex) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_BID_MUST_BE_NUMERIC);
    }

    return placeBid(token, objectId, amount);
  }

  private String placeBid(String token, String objectId, double amount) {
    state.auctionStateLock().lock();
    try {
      CurrentAuction auction = state.activeAuctions().get(objectId);
      if (state.activeAuctions().isEmpty()) {
        return Protocol.error(ErrorCode.NO_AUCTION, ServerMessages.MSG_NO_ACTIVE_AUCTION);
      }
      if (auction == null) {
        return Protocol.error(ErrorCode.INVALID_OBJECT, "Unknown active object");
      }
      if (token.equals(auction.sellerToken())) {
        return Protocol.error(ErrorCode.BAD_REQUEST, "Seller cannot bid on own auction");
      }
      long now = System.currentTimeMillis() / 1000L;
      if (auction.isClosedAt(now)) {
        return Protocol.error(ErrorCode.AUCTION_CLOSED, ServerMessages.MSG_AUCTION_TIME_OVER);
      }
      if (!auction.tryPlaceBid(token, amount, now)) {
        CurrentAuction.BidWindow window = auction.bidWindowAt(now);
        return Protocol.error(ErrorCode.BID_TOO_LOW,
            ServerMessages.MSG_BID_TOO_LOW + ": bid must be > " + window.minimumAcceptedBid() + " and <= "
                + window.maximumAcceptedBid());
      }
      state.broadcast(Protocol.event(
          EventType.BID_ACCEPTED,
          auction.objectId(),
          token,
          Double.toString(amount),
          Long.toString(auction.remainingSeconds())
      ));
      System.out.println("[SERVER] placeBid ok object=" + auction.objectId()
          + " amount=" + amount);
      return Protocol.ok(OkCode.BID_ACCEPTED, auction.objectId(), Double.toString(amount));
    } finally {
      state.auctionStateLock().unlock();
    }
  }

  private String handlePeerListen(ClientSession session, CommandMessage message) {
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }
    if (message.args().length != 1 && message.args().length != 2) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_PEER_LISTEN_REQUIRES_1_ARG);
    }

    String ip;
    String rawPort;
    if (message.args().length == 1) {
      ip = session.socket().getInetAddress().getHostAddress();
      rawPort = message.args()[0];
    } else {
      ip = message.args()[0] == null ? "" : message.args()[0].trim();
      if (ip.isBlank()) {
        ip = session.socket().getInetAddress().getHostAddress();
      }
      rawPort = message.args()[1];
    }

    int port;
    try {
      port = Integer.parseInt(rawPort);
    } catch (NumberFormatException ex) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_PORT_MUST_BE_NUMERIC);
    }
    if (port < ServerValidation.MIN_TCP_PORT || port > ServerValidation.MAX_TCP_PORT) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ServerMessages.MSG_PORT_OUT_OF_RANGE);
    }
    state.userSessionLock().lock();
    try {
      state.activePeers()
          .put(token, new ServerState.PeerEndpoint(ip, port, resolveUsernameForToken(token)));
    } finally {
      state.userSessionLock().unlock();
    }
    return Protocol.ok(OkCode.PEER_REGISTERED, ip, Integer.toString(port));
  }

  private String handleGetUserStats(ClientSession session, CommandMessage message) {
    if (message.args().length > 1) {
      return Protocol.error(ErrorCode.BAD_REQUEST,
          ServerMessages.MSG_GET_USER_STATS_REQUIRES_0_OR_1_ARG);
    }
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }

    String targetUsername = resolveStatsTarget(token, message);
    if (targetUsername == null || targetUsername.isBlank()) {
      return Protocol.error(ErrorCode.BAD_REQUEST,
          ServerMessages.MSG_GET_USER_STATS_REQUIRES_0_OR_1_ARG);
    }

    ServerState.UserStats stats = state.userStats()
        .getOrDefault(targetUsername, ServerState.UserStats.initial());
    return Protocol.ok(OkCode.USER_STATS, targetUsername,
        Integer.toString(stats.numAuctionsAsSeller()),
        Integer.toString(stats.numAuctionsAsBidder()),
        Double.toString(stats.reputationScore()));
  }

  private String resolveStatsTarget(String token, CommandMessage message) {
    if (message.args().length == 1) {
      return normalizeUsername(message.args()[0]);
    }
    state.userSessionLock().lock();
    try {
      ServerState.SessionInfo self = state.sessions().get(token);
      return self == null ? null : self.username();
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private String handleTransactionComplete(ClientSession session, CommandMessage message) {
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }
    if (message.args().length != 1 || message.args()[0] == null || message.args()[0].isBlank()) {
      return Protocol.error(ErrorCode.BAD_REQUEST,
          ServerMessages.MSG_TRANSACTION_COMPLETE_REQUIRES_1_ARG);
    }
    return auctionEngine.recordTransactionSuccess(message.args()[0].trim(), token)
        ? Protocol.ok(OkCode.TRANSACTION_RECORDED, message.args()[0].trim())
        : Protocol.error(ErrorCode.INVALID_OBJECT, "Unknown transaction object");
  }

  private String handleTransactionFailed(ClientSession session, CommandMessage message) {
    String token = requireToken(session);
    if (token == null) {
      return Protocol.error(ErrorCode.NOT_LOGGED_IN, ServerMessages.MSG_LOGIN_REQUIRED);
    }
    if (message.args().length != 1 || message.args()[0] == null || message.args()[0].isBlank()) {
      return Protocol.error(ErrorCode.BAD_REQUEST, "TRANSACTION_FAILED requires 1 arg");
    }
    return auctionEngine.recordTransactionFailure(message.args()[0].trim(), token)
        ? Protocol.ok(OkCode.TRANSACTION_RECORDED, message.args()[0].trim())
        : Protocol.error(ErrorCode.INVALID_OBJECT, "Unknown transaction object");
  }

  private String handleHelp(ClientSession session) {
    boolean authenticated = requireToken(session) != null;
    return Protocol.ok(OkCode.COMMANDS, ServerHelpCatalog.commandLinesFor(authenticated));
  }

  private String requireToken(ClientSession session) {
    String token = session.token();
    if (token == null) {
      return null;
    }

    state.userSessionLock().lock();
    try {
      ServerState.SessionInfo info = state.sessions().get(token);
      if (info == null) {
        return null;
      }

      if (info.isExpired(System.currentTimeMillis())) {
        revokeToken(session, token, info.username(),
            TokenAuditEvent.TOKEN_REVOKED_EXPIRED, "Session token expired");
        return null;
      }
      return token;
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private void revokeToken(ClientSession session, String token, String username,
      TokenAuditEvent event, String detail) {
    state.sessions().remove(token);
    state.activePeers().remove(token);
    session.setToken(null);
    TokenAuditLogger.log(event, username, token, session.remoteAddress(), detail);
  }

  private String resolveUsernameForToken(String token) {
    if (token == null || token.isBlank()) {
      return "";
    }
    ServerState.SessionInfo info = state.sessions().get(token);
    return info == null ? "" : info.username();
  }

  private static String normalizeUsername(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToDefault(String s) {
    String trimmed = s == null ? "" : s.trim();
    return trimmed.isBlank() ? DEFAULT_ITEM_DESCRIPTION : trimmed;
  }
}
