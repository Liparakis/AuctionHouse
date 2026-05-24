package gr.aueb.auctionhouse.server.client;

import gr.aueb.auctionhouse.common.model.CurrentAuction;
import gr.aueb.auctionhouse.common.protocol.codec.Protocol;
import gr.aueb.auctionhouse.common.protocol.enums.ErrorCode;
import gr.aueb.auctionhouse.common.protocol.enums.EventType;
import gr.aueb.auctionhouse.common.protocol.enums.OkCode;
import gr.aueb.auctionhouse.common.protocol.message.CommandMessage;
import gr.aueb.auctionhouse.server.audit.TokenAuditEvent;
import gr.aueb.auctionhouse.server.audit.TokenAuditLogger;
import gr.aueb.auctionhouse.server.config.ServerMessages;
import gr.aueb.auctionhouse.server.state.ServerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable {

  private final Socket socket;
  private final CommandProcessor processor;
  private final ServerState state;

  public ClientHandler(Socket socket, CommandProcessor processor, ServerState state) {
    this.socket = socket;
    this.processor = processor;
    this.state = state;
  }

  @Override
  public void run() {
    ClientSession session = null;
    try (socket;
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

      session = registerSession(socket, writer);
      serveClient(session, reader);

    } catch (IOException ex) {
      System.err.println("[SERVER] Client I/O error: " + ex.getMessage());
    } finally {
      cleanupOnDisconnect(session);
    }
  }

  private ClientSession registerSession(Socket socket, PrintWriter writer) {
    ClientSession session = new ClientSession(socket, writer);
    state.connectedClients().put(session.clientId(), session);
    session.send(Protocol.ok(OkCode.CONNECTED, session.clientId()));
    return session;
  }

  private void serveClient(ClientSession session, BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      String response = dispatchCommand(session, line);
      session.send(response);
    }
  }

  private String dispatchCommand(ClientSession session, String raw) {
    try {
      CommandMessage command = CommandMessage.parse(raw);
      String response = processor.process(session, command);
      return command.formatResponse(response);
    } catch (IllegalArgumentException ex) {
      return Protocol.error(ErrorCode.BAD_REQUEST, ex.getMessage());
    } catch (Exception ex) {
      return Protocol.error(ErrorCode.INTERNAL_ERROR, ServerMessages.MSG_INTERNAL_SERVER_ERROR);
    }
  }

  private void cleanupOnDisconnect(ClientSession session) {
    if (session == null) return;

    state.connectedClients().remove(session.clientId());

    String token = session.token();
    if (token == null) return;

    revokeSessionOnDisconnect(session, token);
    checkActive(token);
  }

  private void revokeSessionOnDisconnect(ClientSession session, String token) {
    state.userSessionLock().lock();
    try {
      ServerState.SessionInfo info = state.sessions().get(token);
      String username = info == null ? null : info.username();
      state.sessions().remove(token);
      state.activePeers().remove(token);
      session.setToken(null);
      TokenAuditLogger.log(TokenAuditEvent.TOKEN_REVOKED_DISCONNECT, username, token,
          session.remoteAddress(), ServerMessages.DETAIL_DISCONNECT_CLEANUP);
    } finally {
      state.userSessionLock().unlock();
    }
  }

  private void checkActive(String disconnectedToken) {
    state.auctionStateLock().lock();
    try {
      for (CurrentAuction current : state.activeAuctionList()) {
        String role = roleInAuction(current, disconnectedToken);
        if (role == null) {
          continue;
        }
        state.activeAuctions().remove(current.objectId());
        state.broadcast(Protocol.event(
            EventType.AUCTION_CANCELLED,
            current.objectId(),
            disconnectedToken,
            role,
            ServerMessages.MSG_AUCTION_CANCELLED_DISCONNECT
        ));
      }
    } finally {
      state.auctionStateLock().unlock();
    }
  }

  private static String roleInAuction(CurrentAuction auction, String token) {
    if (token.equals(auction.sellerToken())) return "seller";
    return null;
  }
}
