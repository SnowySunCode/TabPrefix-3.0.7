package me.snowsun.tabprefix.application;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.persistence.SqliteDatabase;
import me.snowsun.tabprefix.infrastructure.web.HttpProblem;

/** Serial SQLite commits with optimistic version guards and immutable runtime snapshots. */
public final class DisplayService {
  public static final class Snapshot {
    public final DisplayDesign design;
    public final int revision;
    public final String document, prefixDocument;

    public Snapshot(DisplayDesign design, int revision) {
      this.design = design;
      this.revision = revision;
      JsonObject json=design.json();this.document=json.toString();
      this.prefixDocument=design.serverName+":"+json.getAsJsonArray("groupStyles").toString();
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("revision", revision);
      j.add("design", design.json());
      return j;
    }
  }

  private final SqliteDatabase db;
  private final Map<UUID, PlayerDisplaySettings> preferences = new ConcurrentHashMap<>();
  private final PlayerDisplaySettings defaults = new PlayerDisplaySettings(new JsonObject());
  private volatile Snapshot snapshot;
  private volatile Runnable changed = () -> {};
  private volatile java.util.function.Consumer<UUID> preferencesChanged=id->{};

  public DisplayService(SqliteDatabase db, boolean tags) {
    this.db = db;
    snapshot = new Snapshot(DisplayDesign.defaults(tags), 1);
  }

  public Snapshot snapshot() {
    return snapshot;
  }

  /** Called in the serialized SQLite completion chain, after the joint prefix/design commit. */
  public void acceptPrefixEdit(PrefixEditCommit commit) {
    if(commit.design!=null&&commit.displayRevision>snapshot.revision){snapshot=new Snapshot(commit.design,commit.displayRevision);changed.run();}
  }

  public void onChanged(Runnable changed) {
    this.changed = changed;
  }

  public void onPreferencesChanged(java.util.function.Consumer<UUID> changed){this.preferencesChanged=changed;}

  public PlayerDisplaySettings preferences(UUID owner) {
    return preferences.getOrDefault(owner, defaults);
  }

  public CompletableFuture<Void> initialize() {
    return db.execute(
        c -> {
          try (PreparedStatement p =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO display_design(id,revision,document) VALUES(1,1,?)")) {
            p.setString(1, snapshot.design.json().toString());
            p.executeUpdate();
          }
          try (Statement s = c.createStatement();
              ResultSet r =
                  s.executeQuery("SELECT revision,document FROM display_design WHERE id=1")) {
            if (!r.next()) throw new SQLException("Missing display design");
            snapshot =
                new Snapshot(
                    new DisplayDesign(new JsonParser().parse(r.getString(2)).getAsJsonObject()),
                    r.getInt(1));
          }
          try (Statement s = c.createStatement();
              ResultSet r =
                  s.executeQuery("SELECT player_uuid,document FROM player_display_preferences")) {
            while (r.next())
              preferences.put(
                  UUID.fromString(r.getString(1)),
                  new PlayerDisplaySettings(
                      new JsonParser().parse(r.getString(2)).getAsJsonObject()));
          }
          return null;
        });
  }

  public CompletableFuture<Snapshot> save(DisplayDesign design, int expected, UUID author) {
    String document=design.json().toString();
    return db.execute(
        c -> {
          if(snapshot.document.equals(document))return snapshot;
          try (PreparedStatement p =
              c.prepareStatement(
                  "UPDATE display_design SET"
                      + " revision=revision+1,document=?,updated_by=?,updated_at=? WHERE id=1 AND"
                      + " revision=?")) {
            p.setString(1, document);
            p.setString(2, author.toString());
            p.setLong(3, System.currentTimeMillis());
            p.setInt(4, expected);
            if (p.executeUpdate() != 1)
              throw new HttpProblem(
                  409,
                  "Another administrator saved this design. Sync the latest revision.");
          }
          Snapshot next = new Snapshot(design, expected + 1);
          snapshot = next;
          changed.run();
          return next;
        });
  }

  public CompletableFuture<PlayerDisplaySettings> savePreferences(
      UUID owner, PlayerDisplaySettings settings) {
    return savePreferences(owner, settings, null);
  }

  public CompletableFuture<PlayerDisplaySettings> savePreferences(
      UUID owner, PlayerDisplaySettings settings, String expectedRevision) {
    return db.execute(
        c -> {
          JsonObject current = preferences(owner).json();
          if(current.equals(settings.json()))return settings;
          if (expectedRevision != null && !me.snowsun.tabprefix.util.Digests.sha256(current.toString()).equals(expectedRevision))
            throw new HttpProblem(409, "These preferences changed in another session. Sync the latest revision.");
          try (PreparedStatement p =
              c.prepareStatement(
                  "INSERT INTO player_display_preferences(player_uuid,document) VALUES(?,?) ON"
                      + " CONFLICT(player_uuid) DO UPDATE SET document=excluded.document")) {
            p.setString(1, owner.toString());
            p.setString(2, settings.json().toString());
            p.executeUpdate();
          }
          preferences.put(owner, settings);
          preferencesChanged.accept(owner);
          changed.run();
          return settings;
        });
  }
}
