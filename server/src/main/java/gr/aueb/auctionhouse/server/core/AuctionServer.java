package gr.aueb.auctionhouse.server.core;

import gr.aueb.auctionhouse.server.auction.AuctionEngine;
import gr.aueb.auctionhouse.server.client.ClientHandler;
import gr.aueb.auctionhouse.server.client.CommandProcessor;
import gr.aueb.auctionhouse.server.state.ServerState;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AuctionServer {

  private final int port;
  private final String bindIp;
  private final ServerState state = new ServerState();
  private final AuctionEngine auctionEngine = new AuctionEngine(state);
  private final CommandProcessor processor = new CommandProcessor(state, auctionEngine);
  private final ExecutorService connectionPool = Executors.newCachedThreadPool();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ServerSocket serverSocket;

  public AuctionServer(int port, String bindIp) {
    this.port = port;
    this.bindIp = bindIp == null || bindIp.isBlank() ? "0.0.0.0" : bindIp.trim();
  }

  public void start() {
    System.out.println("[SERVER] Starting auction server on " + bindIp + ":" + port);
    running.set(true);
    auctionEngine.start();
    try (ServerSocket ss = openServerSocket()) {
      serverSocket = ss;
      while (running.get()) {
        Socket clientSocket = ss.accept();
        System.out.println("[SERVER] Accepted client " + clientSocket.getRemoteSocketAddress());
        connectionPool.submit(new ClientHandler(clientSocket, processor, state));
      }
    } catch (IOException ex) {
      if (running.get()) {
        throw new RuntimeException("Server failed to start/run: " + ex.getMessage(), ex);
      }
    } finally {
      stop();
    }
  }

  public void stop() {
    if (!running.getAndSet(false)) {
      return;
    }
    try {
      ServerSocket ss = serverSocket;
      if (ss != null && !ss.isClosed()) {
        ss.close();
      }
    } catch (IOException ignored) {
    }
    auctionEngine.stop();
    connectionPool.shutdownNow();
    state.shutdown();
  }

  private ServerSocket openServerSocket() throws IOException {
    InetAddress bindAddress = InetAddress.getByName(bindIp);
    return new ServerSocket(port, 50, bindAddress);
  }
}
