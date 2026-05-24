package gr.aueb.auctionhouse.peer.ui;

import org.jline.reader.LineReader;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

// draws peer console
public final class PeerFrameConsole implements AutoCloseable {

  private static final int MAX_LINES = 32;

  private static final String ENTER_ALT = "\u001B[?1049h";
  private static final String EXIT_ALT = "\u001B[?1049l";
  private static final String CLEAR = "\u001B[H\u001B[2J";
  private static final String RESET = "\u001B[0m";
  private static final String DIM = "\u001B[2m";
  private static final String BOLD = "\u001B[1m";

  private static final String INFO_COLOR = "\u001B[37m";
  private static final String SERVER_COLOR = "\u001B[36m";
  private static final String WARN_COLOR = "\u001B[33m";
  private static final String ERROR_COLOR = "\u001B[31m";
  private static final String AUCTION_COLOR = "\u001B[32m";

  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final String host;
  private final int port;
  private final Deque<ConsoleLine> lines = new ArrayDeque<>();
  private final Object renderLock = new Object();

  private volatile LineReader lineReader;
  private volatile boolean inputActive;

  public PeerFrameConsole(String host, int port) {
    this.host = host;
    this.port = port;
    System.out.print(ENTER_ALT);
    System.out.flush();
    render();
  }

  public void addInfo(String message) {
    add("INFO", message);
  }

  public void addServer(String message) {
    add("SERVER", message);
  }

  public void addWarn(String message) {
    add("WARN", message);
  }

  public void addError(String message) {
    add("ERROR", message);
  }

  public void addAuction(String message) {
    add("AUCTION", message);
  }

  public void beginInput() {
    inputActive = true;
  }

  public void endInput() {
    inputActive = false;
    render();
  }

  public void attachLineReader(LineReader reader) {
    this.lineReader = reader;
  }

  private void add(String level, String message) {
    String stamp = LocalTime.now().format(TIME_FORMAT);
    String[] parts = message == null ? new String[]{""} : message.split("\\R", -1);
    lines.clear();

    for (int i = 0; i < parts.length; i++) {
      String text = i == 0
          ? "[" + stamp + "] " + level + "  " + parts[i]
          : normalizeDetailLine(parts[i]);
      lines.addLast(new ConsoleLine(i == 0 ? level : "DETAIL", text));
      while (lines.size() > MAX_LINES) {
        lines.removeFirst();
      }
    }
    render();
  }

  private static String normalizeDetailLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    return trimmed.startsWith("- ") ? trimmed : "- " + trimmed;
  }

  private void render() {
    synchronized (renderLock) {
      // keeps render in one piece
      String output = buildFrame();
      System.out.print(output);
      System.out.flush();
      redrawPromptIfActive();
    }
  }

  private String buildFrame() {
    StringBuilder out = new StringBuilder(2048);
    out.append(CLEAR);
    out.append(BOLD).append("Auction Peer Interactive").append(RESET).append('\n');
    out.append(DIM).append("Target: ").append(host).append(':').append(port).append(RESET)
        .append('\n');
    out.append(DIM).append("Type commands. Use 'exit' to quit.").append(RESET).append('\n');
    out.append(DIM).append("------------------------------------------------------------")
        .append(RESET).append('\n');

    if (lines.isEmpty()) {
      out.append(DIM).append("(no messages yet)").append(RESET).append('\n');
    } else {
      for (ConsoleLine line : lines) {
        out.append(colorize(line)).append('\n');
      }
    }

    out.append(DIM).append("------------------------------------------------------------")
        .append(RESET).append('\n');

    if (lineReader == null) {
      out.append(BOLD).append("> ").append(RESET);
    }

    return out.toString();
  }

  private void redrawPromptIfActive() {
    LineReader reader = lineReader;
    if (reader != null && inputActive) {
      try {
        reader.callWidget(LineReader.REDRAW_LINE);
        reader.callWidget(LineReader.REDISPLAY);
      } catch (IllegalStateException ignored) {
      }
    }
  }

  private static String colorize(ConsoleLine line) {
    String color = switch (line.level()) {
      case "WARN" -> WARN_COLOR;
      case "ERROR" -> ERROR_COLOR;
      case "SERVER" -> SERVER_COLOR;
      case "AUCTION" -> AUCTION_COLOR;
      case "INFO" -> INFO_COLOR;
      case "DETAIL" -> DIM;
      default -> RESET;
    };
    return color + line.text() + RESET;
  }

  @Override
  public void close() {
    System.out.print(EXIT_ALT);
    System.out.flush();
  }

  private record ConsoleLine(String level, String text) {

  }
}
