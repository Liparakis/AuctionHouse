package gr.aueb.auctionhouse.peer.transaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

// stores auction meta files
public final class ObjectMetadataStore {

  private ObjectMetadataStore() {
  }

  public static Path peerRoot(String peerId) throws IOException {
    Path root = sharedObjectsBase().resolve(sanitize(peerId));
    Files.createDirectories(root);
    return root;
  }

  public static Path metadataPath(Path peerRoot, String objectId) {
    return peerRoot.resolve(sanitize(objectId) + ".meta");
  }

  public static Path createPending(Path peerRoot, String description, double startPrice,
      int durationSec) throws IOException {
    // makes temporary meta file
    String pendingId = "pending-" + UUID.randomUUID();
    Path pendingPath = metadataPath(peerRoot, pendingId);
    Files.writeString(pendingPath, serialize(description, startPrice, durationSec),
        StandardCharsets.UTF_8);
    return pendingPath;
  }

  public static Path bindPendingToObject(Path pendingPath, Path peerRoot, String objectId)
      throws IOException {
    Path target = metadataPath(peerRoot, objectId);
    if (pendingPath.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
      return target;
    }
    try {
      Files.move(pendingPath, target, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(pendingPath, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  public static MetadataFields readMetadataFields(Path metadataPath) throws IOException {
    String raw = Files.readString(metadataPath, StandardCharsets.UTF_8);
    return parseMetadataFields(raw);
  }

  public static void writeMetadata(Path peerRoot, String objectId, byte[] content)
      throws IOException {
    Path target = metadataPath(peerRoot, objectId);
    Files.createDirectories(target.getParent());
    Files.write(target, content);
  }

  private static MetadataFields parseMetadataFields(String raw) throws IOException {
    String description = "Some item";
    Double startBid = null;
    Integer duration = null;

    for (String line : raw.split("\\R")) {
      String trimmed = line.trim();
      if (!trimmed.contains("=")) {
        continue;
      }

      int idx = trimmed.indexOf('=');
      String key = trimmed.substring(0, idx).trim().toLowerCase(Locale.ROOT);
      String value = trimmed.substring(idx + 1).trim();

      switch (key) {
        case "description" -> {
          if (!value.isBlank()) {
            description = value;
          }
        }
        case "start_bid" -> startBid = Double.parseDouble(value);
        case "duration" -> duration = Integer.parseInt(value);
      }
    }

    if (startBid == null || duration == null) {
      throw new IOException("Invalid metadata format: missing start_bid or duration");
    }
    if (startBid <= 0 || duration <= 0) {
      throw new IOException("Invalid metadata values: start_bid and duration must be > 0");
    }
    return new MetadataFields(description, startBid, duration);
  }

  private static String serialize(String description, double startPrice, int durationSec) {
    String safeDescription =
        (description == null || description.isBlank()) ? "Some item" : description;
    return String.join("\n",
        "description=" + safeDescription,
        "start_bid=" + startPrice,
        "duration=" + durationSec
    );
  }

  private static String sanitize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "peer";
    }
    return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
  }

  private static Path sharedObjectsBase() {
    String configured = System.getProperty("auction.shared.root");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }
    // default local folder
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path leaf = cwd.getFileName();
    if (leaf != null && "bin".equalsIgnoreCase(leaf.toString())) {
      return cwd.resolve("shared_objects");
    }
    return cwd.resolve("bin").resolve("shared_objects");
  }

  public record MetadataFields(String description, double startBid, int durationSec) {

  }
}
