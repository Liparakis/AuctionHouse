package gr.aueb.auctionhouse.peer.connection;

import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.enums.ResponseKind;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// socket stuff for peer
public class PeerConnection implements AutoCloseable {

  private static final long MIN_TIMEOUT_MS = 1L;

  private final Socket socket;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private final LinkedBlockingQueue<ResponseMessage> responses = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<ResponseMessage> events = new LinkedBlockingQueue<>();
  private final AtomicLong requestSeq = new AtomicLong(1);
  private final Thread listener;
  private volatile boolean alive = true;

  public PeerConnection(Socket socket) throws IOException {
    this.socket = socket;
    this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    this.writer = new PrintWriter(socket.getOutputStream(), true);
    this.listener = startListenerThread();
  }

  private Thread startListenerThread() {
    Thread t = new Thread(this::listenLoop, "peer-listener");
    t.setDaemon(true);
    t.start();
    return t;
  }

  private void listenLoop() {
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        handleInboundLine(line);
      }
    } catch (IOException ignored) {
    } finally {
      alive = false;
    }
  }

  private void handleInboundLine(String line) {
    String normalized = line.trim();
    if (normalized.isEmpty()) {
      return;
    }

    ResponseMessage msg = tryParse(normalized);
    if (msg == null) {
      return;
    }

    routeMessage(msg);
  }

  private ResponseMessage tryParse(String raw) {
    try {
      return ResponseMessage.parse(raw);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private void routeMessage(ResponseMessage msg) {
    if (msg.kind() == ResponseKind.EVENT) {
      events.offer(msg);
    } else {
      responses.offer(msg);
    }
  }

  public void send(String command) {
    synchronized (writer) {
      writer.println(command);
      writer.flush();
      checkWriteHealth();
    }
  }

  public String sendWithRequest(String command) {
    String requestId = "r" + requestSeq.getAndIncrement();
    send(requestId + "#" + command);
    return requestId;
  }

  private void checkWriteHealth() {
    if (writer.checkError()) {
      alive = false;
      throw new IllegalStateException(
          "Socket write failed; server connection is no longer healthy.");
    }
  }

  public ResponseMessage readResponse(long timeoutMs) {
    try {
      return responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  public ResponseMessage readResponseForRequest(String requestId, long timeoutMs) {
    long deadline = deadlineFrom(timeoutMs);
    ResponseMessage msg;
    while ((msg = readNextMeaningfulResponse(deadline)) != null) {
      if (isMatchingResponse(msg, requestId)) {
        return msg;
      }
    }
    return null;
  }

  public String readMeaningfulResponse(long timeoutMs) {
    long deadline = deadlineFrom(timeoutMs);
    ResponseMessage msg = readNextMeaningfulResponse(deadline);
    return msg == null ? null : msg.raw();
  }

  public ResponseMessage pollEvent(long timeoutMs) {
    try {
      return events.poll(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private ResponseMessage readNextMeaningfulResponse(long deadline) {
    while (System.currentTimeMillis() < deadline) {
      ResponseMessage msg = readResponse(remainingTime(deadline));
      if (msg == null) {
        return null;
      }
      if (!isConnectedAck(msg)) {
        return msg;
      }
    }
    return null;
  }

  private boolean isConnectedAck(ResponseMessage msg) {
    return msg.kind() == ResponseKind.OK && msg.okCode() == OkCode.CONNECTED;
  }

  private boolean isMatchingResponse(ResponseMessage msg, String requestId) {
    String msgReqId = msg.requestId();
    return requestId.equals(msgReqId) || msgReqId == null || msgReqId.isBlank();
  }

  private long deadlineFrom(long timeoutMs) {
    return System.currentTimeMillis() + Math.max(MIN_TIMEOUT_MS, timeoutMs);
  }

  private long remainingTime(long deadline) {
    return Math.max(MIN_TIMEOUT_MS, deadline - System.currentTimeMillis());
  }

  public boolean isAlive() {
    return alive && !socket.isClosed();
  }

  public InetAddress localAddress() {
    return socket.getLocalAddress();
  }

  @Override
  public void close() {
    alive = false;
    listener.interrupt();
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
