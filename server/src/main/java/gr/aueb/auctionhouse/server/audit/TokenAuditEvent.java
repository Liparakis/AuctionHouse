package gr.aueb.auctionhouse.server.audit;

public enum TokenAuditEvent {
  TOKEN_ISSUED,
  TOKEN_REVOKED_LOGOUT,
  TOKEN_REVOKED_DISCONNECT,
  TOKEN_REVOKED_EXPIRED,
  TOKEN_INVALIDATED_RELOGIN
}