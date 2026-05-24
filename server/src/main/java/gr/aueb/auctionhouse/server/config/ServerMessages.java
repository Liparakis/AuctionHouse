package gr.aueb.auctionhouse.server.config;

public final class ServerMessages {

  public static final String WELCOME_BANNER = "auction-server";

  // -------------------------------------------------------------------------
  // Argument-count errors
  // -------------------------------------------------------------------------

  public static final String MSG_REGISTER_REQUIRES_2_ARGS           = "REGISTER requires 2 args";
  public static final String MSG_LOGIN_REQUIRES_2_ARGS              = "LOGIN requires 2 args";
  public static final String MSG_BID_REQUIRES_1_ARG                 = "BID requires 2 args (objectId,amount)";
  public static final String MSG_SEND_AUCTION_REQUEST_REQUIRES_2_OR_3_ARGS = "SEND_AUCTION_REQUEST requires 2 args (start,duration) or 3 args (description,start,duration)";
  public static final String MSG_PEER_LISTEN_REQUIRES_1_ARG         = "PEER_LISTEN requires 1 arg (port) or 2 args (ip,port)";
  public static final String MSG_TRANSACTION_COMPLETE_REQUIRES_1_ARG = "TRANSACTION_COMPLETE requires 1 arg";
  public static final String MSG_GET_USER_STATS_REQUIRES_0_OR_1_ARG = "GET_USER_STATS requires 0 or 1 args";
  public static final String MSG_GET_DETAILS_REQUIRES_0_ARGS         = "GET_DETAILS requires 1 arg (objectId)";

  // -------------------------------------------------------------------------
  // Auth / session errors
  // -------------------------------------------------------------------------

  public static final String MSG_USERNAME_EXISTS      = "Username is already registered";
  public static final String MSG_INVALID_CREDENTIALS  = "Invalid credentials";
  public static final String MSG_NO_ACTIVE_SESSION    = "No active session";
  public static final String MSG_LOGIN_REQUIRED       = "Login required";
  public static final String MSG_ALREADY_LOGGED_IN_THIS_CLIENT = "Already logged in on this client; logout first";
  public static final String MSG_ACCOUNT_ALREADY_LOGGED_IN = "Account is already logged in from another client";

  // -------------------------------------------------------------------------
  // Input-validation errors
  // -------------------------------------------------------------------------

  public static final String MSG_PORT_MUST_BE_NUMERIC         = "Port must be numeric";
  public static final String MSG_PORT_OUT_OF_RANGE            = "Port must be 1..65535";
  public static final String MSG_BID_MUST_BE_NUMERIC          = "Bid amount must be numeric";
  public static final String MSG_BID_TOO_LOW                  = "Bid must be within the allowed range for the current auction phase";
  public static final String MSG_BAD_AUCTION_REQUEST_NUMERIC  = "startPrice and durationSec must be numeric";
  public static final String MSG_BAD_AUCTION_REQUEST_POSITIVE = "startPrice and durationSec must be > 0";
  public static final String MSG_PEER_ENDPOINT_REQUIRED       = "Peer endpoint missing; login/register peer listener first";

  // -------------------------------------------------------------------------
  // Auction-state errors
  // -------------------------------------------------------------------------

  public static final String MSG_AUCTION_TIME_OVER       = "Auction time is over";
  public static final String MSG_NO_ACTIVE_AUCTION       = "No active auction";

  // -------------------------------------------------------------------------
  // Generic errors
  // -------------------------------------------------------------------------

  public static final String MSG_INTERNAL_SERVER_ERROR = "Unexpected server error";
  public static final String MSG_AUCTION_CANCELLED_DISCONNECT = "Auction cancelled due to participant disconnect";

  // -------------------------------------------------------------------------
  // Audit detail strings !!! NOT FOR CLIENTS!
  // -------------------------------------------------------------------------

  public static final String DETAIL_LOGIN_SUCCESS       = "LOGIN success";
  public static final String DETAIL_LOGOUT              = "LOGOUT";
  public static final String DETAIL_DISCONNECT_CLEANUP  = "Socket disconnect cleanup";

  private ServerMessages() {}
}
