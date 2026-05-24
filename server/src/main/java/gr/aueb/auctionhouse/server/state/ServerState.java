package gr.aueb.auctionhouse.server.state;

import gr.aueb.auctionhouse.common.model.AuctionRequest;
import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.server.client.ClientSession;
import gr.aueb.auctionhouse.server.config.ServerValidation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ServerState {

  // Registered accounts: username -> hashed password
  private final Map<String, String> users = new ConcurrentHashMap<>();

  // Active session tokens: token -> session info
  private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

  // Per-user auction participation counters
  private final Map<String, UserStats> userStats = new ConcurrentHashMap<>();

  // Registered peer transfer endpoints by session token
  private final Map<String, PeerEndpoint> activePeers = new ConcurrentHashMap<>();

  // Connected clients: clientId -> session
  private final Map<String, ClientSession> connectedClients = new ConcurrentHashMap<>();

  // Pending auction requests
  private final Queue<AuctionRequest> auctionQueue = new ConcurrentLinkedQueue<>();

  // Up to two currently running auctions keyed by object id
  private final Map<String, CurrentAuction> activeAuctions = new ConcurrentHashMap<>();

  // Ended auctions waiting for transaction success/failure resolution
  private final Map<String, CurrentAuction> pendingTransactions = new ConcurrentHashMap<>();

  // Guards users/sessions/userStats/activePeers compound operations
  private final ReentrantLock userSessionLock = new ReentrantLock();

  // Guards auctionQueue/currentAuction compound operations
  private final ReentrantLock auctionStateLock = new ReentrantLock();

  // Bounded async broadcaster; CallerRunsPolicy applies backpressure instead of dropping
  private final ExecutorService outboundPool = new ThreadPoolExecutor(
      ServerValidation.BROADCAST_WORKERS,
      ServerValidation.BROADCAST_WORKERS,
      60L, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(ServerValidation.BROADCAST_QUEUE_CAPACITY),
      new ThreadPoolExecutor.CallerRunsPolicy()
  );

  public Map<String, String> users() {
    return users;
  }

  public Map<String, SessionInfo> sessions() {
    return sessions;
  }

  public Map<String, UserStats> userStats() {
    return userStats;
  }

  public Map<String, PeerEndpoint> activePeers() {
    return activePeers;
  }

  public Map<String, ClientSession> connectedClients() {
    return connectedClients;
  }

  public Queue<AuctionRequest> auctionQueue() {
    return auctionQueue;
  }

  public Map<String, CurrentAuction> activeAuctions() {
    return activeAuctions;
  }

  public Map<String, CurrentAuction> pendingTransactions() {
    return pendingTransactions;
  }

  public ReentrantLock userSessionLock() {
    return userSessionLock;
  }

  public ReentrantLock auctionStateLock() {
    return auctionStateLock;
  }

  public void broadcast(String line) {
    for (ClientSession session : connectedClients.values()) {
      submitBroadcast(session, line);
    }
  }

  private void submitBroadcast(ClientSession session, String line) {
    try {
      outboundPool.submit(() -> sendQuietly(session, line));
    } catch (RejectedExecutionException ignored) {
      /* Ignored */
    }
  }

  private static void sendQuietly(ClientSession session, String line) {
    try {
      session.send(line);
    } catch (Exception ignored) {
      /* Ignored */
    }
  }

  public void shutdown() {
    outboundPool.shutdownNow();
    connectedClients.values().forEach(ServerState::closeQuietly);
    connectedClients.clear();
  }

  public List<CurrentAuction> activeAuctionList() {
    List<CurrentAuction> auctions = new ArrayList<>(activeAuctions.values());
    auctions.sort(Comparator.comparing(CurrentAuction::objectId));
    return auctions;
  }

  public boolean hasAuctionCapacity() {
    return activeAuctions.size() < 2;
  }

  public AuctionRequest pollNextAuctionByReputation() {
    AuctionRequest first = auctionQueue.poll();
    if (first == null) {
      return null;
    }
    AuctionRequest second = auctionQueue.poll();
    if (second == null) {
      return first;
    }

    double firstReputation = reputationForSeller(first.sellerToken());
    double secondReputation = reputationForSeller(second.sellerToken());
    if (secondReputation > firstReputation) {
      auctionQueue.add(first);
      return second;
    }
    auctionQueue.add(second);
    return first;
  }

  public LinkedHashSet<String> connectedPeerTokens() {
    return new LinkedHashSet<>(activePeers.keySet());
  }

  public double reputationForSeller(String sellerToken) {
    String username = usernameForToken(sellerToken);
    if (username == null || username.isBlank()) {
      return 0.0;
    }
    return userStats.getOrDefault(username, UserStats.initial()).reputationScore();
  }

  public String usernameForToken(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    SessionInfo session = sessions.get(token);
    return session == null ? null : session.username();
  }

  private static void closeQuietly(ClientSession session) {
    try {
      session.socket().close();
    } catch (Exception ignored) {
    }
  }

  public record SessionInfo(String username, long expiresAtEpochMs) {

    public boolean isExpired(long nowEpochMs) {
      return nowEpochMs >= expiresAtEpochMs;
    }
  }

  public record UserStats(int numAuctionsAsSeller, int numAuctionsAsBidder, double reputationScore) {

    private static final double REPUTATION_BETA = 0.25;

    public static UserStats initial() {
      return new UserStats(0, 0, 1.0);
    }

    public UserStats incrementSeller() {
      return new UserStats(numAuctionsAsSeller + 1, numAuctionsAsBidder, reputationScore);
    }

    public UserStats recordBidderSuccess() {
      return new UserStats(numAuctionsAsSeller, numAuctionsAsBidder + 1,
          blendReputation(1.0));
    }

    public UserStats recordAwardFailure() {
      return new UserStats(numAuctionsAsSeller, numAuctionsAsBidder,
          blendReputation(0.0));
    }

    private double blendReputation(double outcome) {
      return (1.0 - REPUTATION_BETA) * reputationScore + REPUTATION_BETA * outcome;
    }
  }

  public record PeerEndpoint(String ip, int port, String username) {

  }
}
