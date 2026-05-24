package gr.aueb.auctionhouse.peer.ui;

import gr.aueb.auctionhouse.common.protocol.enums.CommandType;
import gr.aueb.auctionhouse.common.protocol.message.ResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PeerConsoleFormatter {

  private PeerConsoleFormatter() {
  }

  public static String formatRaw(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    try {
      return format(ResponseMessage.parse(raw));
    } catch (Exception ignored) {
      return raw;
    }
  }

  public static String format(ResponseMessage response) {
    if (response == null) {
      return "(no response)";
    }

    String prefix = requestPrefix(response);

    return switch (response.kind()) {
      case OK -> prefix + formatOk(response);
      case ERR -> prefix + "ERR " + response.errorCode().name() + renderFields(response.fields());
      case EVENT ->
          prefix + "EVENT " + response.eventType().name() + renderFields(response.fields());
      default -> prefix + response.raw();
    };
  }

  private static String requestPrefix(ResponseMessage response) {
    String id = response.requestId();
    return (id == null || id.isBlank()) ? "" : "[" + id + "] ";
  }

  private static String formatOk(ResponseMessage response) {
    return switch (response.okCode()) {
      case COMMANDS -> "OK " + renderCommands(response.fields());
      case ACTIVE_AUCTIONS -> "OK " + renderActiveAuctions(response.fields());
      case CURRENT_AUCTION -> "OK " + renderCurrentAuction(response.fields());
      case USER_STATS -> "OK " + renderUserStats(response.fields());
      default -> "OK " + response.okCode().name() + renderFields(response.fields());
    };
  }

  private static String renderFields(String[] fields) {
    if (fields == null || fields.length == 0) {
      return "";
    }
    return " | " + String.join(" | ", fields);
  }

  private static String renderCommands(String[] fields) {
    if (fields == null || fields.length == 0) {
      return "commands: (none)";
    }

    List<String> commands = new ArrayList<>();
    String current = null;
    List<String> args = new ArrayList<>();

    for (String token : fields) {
      // reads commands list
      CommandType type = CommandType.fromWire(token);
      if (type != CommandType.UNKNOWN) {
        if (current != null) {
          commands.add(formatCommand(current, args));
        }
        current = type.name().toLowerCase(Locale.ROOT);
        args.clear();
      } else if (current != null) {
        args.add("<" + token + ">");
      }
    }
    if (current != null) {
      commands.add(formatCommand(current, args));
    }

    StringBuilder out = new StringBuilder("commands:\n");
    for (String command : commands) {
      out.append("  - ").append(command).append('\n');
    }
    if (!commands.isEmpty()) {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }

  private static String formatCommand(String command, List<String> args) {
    return args.isEmpty() ? command : command + " " + String.join(" ", args);
  }

  private static String renderCurrentAuction(String[] fields) {
    if (fields != null && fields.length >= 7) {
      return String.join("\n",
          "auction_details:",
          "- object: " + field(fields, 0),
          "- description: " + field(fields, 1),
          "- seller: " + shortId(field(fields, 2)),
          "- highestBid: " + field(fields, 3),
          "- highestBidder: " + shortId(field(fields, 4)),
          "- remainingSec: " + field(fields, 5),
          "- durationSec: " + field(fields, 6)
      );
    }
    return String.join("\n",
        "current_auction:",
        "- object: " + field(fields, 0),
        "- description: " + field(fields, 1)
    );
  }

  private static String renderUserStats(String[] fields) {
    return String.join("\n",
        "user_stats:",
        "- username: " + field(fields, 0),
        "- numAuctionsAsSeller: " + field(fields, 1),
        "- numAuctionsAsBidder: " + field(fields, 2),
        "- reputationScore: " + field(fields, 3)
    );
  }

  private static String renderActiveAuctions(String[] fields) {
    int count = parseInt(field(fields, 0));
    if (count <= 0) {
      return "active_auctions: (none)";
    }
    StringBuilder out = new StringBuilder("active_auctions:\n");
    for (int i = 0; i < count; i++) {
      int base = 1 + (i * 5);
      out.append("- object: ").append(field(fields, base)).append('\n');
      out.append("  description: ").append(field(fields, base + 1)).append('\n');
      out.append("  highestBid: ").append(field(fields, base + 2)).append('\n');
      out.append("  remainingSec: ").append(field(fields, base + 3)).append('\n');
      out.append("  durationSec: ").append(field(fields, base + 4)).append('\n');
    }
    out.setLength(out.length() - 1);
    return out.toString();
  }

  private static String field(String[] fields, int idx) {
    return (fields == null || idx < 0 || idx >= fields.length) ? "" : fields[idx];
  }

  private static String shortId(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value.length() <= 8 ? value : value.substring(0, 8);
  }

  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Exception ignored) {
      return 0;
    }
  }
}
