package gr.aueb.auctionhouse.server.client;

import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

/** Runtime state for one connected client. */
public class ClientSession {

  private final String clientId = UUID.randomUUID().toString();
  private final Socket socket;
  private final PrintWriter writer;

  private volatile String token;

  public ClientSession(Socket socket, PrintWriter writer) {
    this.socket = socket;
    this.writer = writer;
  }

  public String clientId() {
    return clientId;
  }

  public Socket socket() {
    return socket;
  }

  public String remoteAddress() {
    return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
  }

  public void send(String line) {
    synchronized (writer) {
      writer.println(line);
      writer.flush();
    }
  }

  public String token() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }
}