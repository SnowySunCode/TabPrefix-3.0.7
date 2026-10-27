package me.snowsun.tabprefix.infrastructure.web;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.application.port.AuditSink;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.ResourcePackService;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Authenticated editor API plus strictly scoped immutable pack downloads. */
public final class EditorServer implements AutoCloseable {
  private final GraphicService graphics;
  private final PrefixService texts;
  private final MediaStore media;
  private final ResourcePackService packs;
  private final AuditSink audit;
  private final SessionRegistry sessions;
  private final WebAddresses addresses;
  private final Logger logger;
  private final Map<String, byte[]> staticFiles = new HashMap<>();
  private final AsyncWorker imageWorker = new AsyncWorker("Images", 1, 8);
  private volatile PluginSettings settings;
  private HttpServer server;
  private ThreadPoolExecutor pool;
  private volatile String state = "starting", failure = "";
  private volatile boolean listening;
  private boolean closed;
  private DisplayService displays;
  private java.util.function.BiFunction<UUID,String,CompletableFuture<Boolean>> prefixAccess;
  private Runnable prefixApplied=()->{};
  public void prefixEditing(java.util.function.BiFunction<UUID,String,CompletableFuture<Boolean>> guard,Runnable applied){prefixAccess=guard;prefixApplied=applied;}
  private java.util.function.BiFunction<UUID, String, CompletableFuture<Boolean>> access;

  @FunctionalInterface
  public interface DisplayPreview {
    CompletableFuture<JsonObject> render(UUID player, DisplayDesign design, long tick);
  }

  private DisplayPreview displayPreview;
  private java.util.function.Supplier<CompletableFuture<JsonArray>> displayGroups =
      () -> CompletableFuture.completedFuture(new JsonArray());

  public void displayGroups(java.util.function.Supplier<CompletableFuture<JsonArray>> groups) {
    displayGroups = groups;
  }

  public void displays(
      DisplayService service,
      java.util.function.BiFunction<UUID, String, CompletableFuture<Boolean>> access,
      DisplayPreview preview) {
    this.displays = service;
    packs.designs(() -> service.snapshot().design);
    this.access = access;
    this.displayPreview = preview;
  }

  public EditorServer(
      PluginSettings settings,
      GraphicService graphics,
      PrefixService texts,
      MediaStore media,
      ResourcePackService packs,
      AuditSink audit,
      String base,
      Logger logger)
      throws IOException {
    this(
        settings,
        graphics,
        texts,
        media,
        packs,
        audit,
        WebAddresses.fixed(settings.features, base),
        logger);
  }

  public EditorServer(
      PluginSettings settings,
      GraphicService graphics,
      PrefixService texts,
      MediaStore media,
      ResourcePackService packs,
      AuditSink audit,
      WebAddresses addresses,
      Logger logger)
      throws IOException {
    this.settings = settings;
    this.graphics = graphics;
    this.texts = texts;
    this.media = media;
    this.packs = packs;
    this.audit = audit;
    this.addresses = addresses;
    this.logger = logger;
    sessions = new SessionRegistry(settings.features);
    if (!settings.features.web) state = "disabled";
    for (String name :
        Arrays.asList(
            "editor.html",
            "editor.css",
            "editor.js",
            "studio.html",
            "studio.css",
            "studio.js",
            "live-save.js",
            "dream.css",
            "dream.js",
            "workbench.css",
            "workbench.js",
            "polish.css",
            "polish.js",
            "artwork.js",
            "artwork.css", "atelier.css", "atelier.js", "studio-font.ttf", "evolution.js", "evolution.css", "composition.js", "composition.css"))
      try (InputStream in =
          EditorServer.class.getClassLoader().getResourceAsStream("web/" + name)) {
        if (in == null) throw new IOException("Missing web resource: " + name);
        staticFiles.put(name, read(in, 1048576));
      }
  }

  public synchronized void start() throws IOException {
    if (closed) throw new IllegalStateException("Web editor has been stopped.");
    if (listening) return;
    FeatureSettings f = settings.features;
    if (!f.web) return;
    try {
      server = bind(f);
      pool =
          new ThreadPoolExecutor(
              f.workers,
              f.workers,
              0,
              TimeUnit.MILLISECONDS,
              new ArrayBlockingQueue<>(64),
              r -> {
                Thread t = new Thread(r, "TabPrefix-HTTP");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
      server.setExecutor(pool);
      server.createContext("/", this::handle);
      server.start();
      addresses.bound(server.getAddress());
      failure = "";
      state = "listening";
      listening = true;
      logger.info("Web editor: " + diagnostic());
      if (addresses.port() != f.port && f.port != 0)
        logger.info("HTTP port " + f.port + " was occupied; selected " + addresses.port() + ".");
    } catch (IOException | RuntimeException error) {
      listening = false;
      state = error instanceof IOException ? "bind-failed" : "start-failed";
      failure = Failures.message(error).replaceAll("[\\p{Cntrl}]", " ");
      if (server != null) {
        server.stop(0);
        server = null;
      }
      if (pool != null) {
        pool.shutdownNow();
        pool = null;
      }
      throw error;
    }
  }

  private HttpServer bind(FeatureSettings f) throws IOException {
    boolean auto = addresses.automaticPort();
    int attempts = auto && f.port != 0 ? Math.min(20, 65536 - f.port) + 1 : 1;
    for (int i = 0; i < attempts; i++) {
      int candidate = auto && i == attempts - 1 && attempts > 1 ? 0 : f.port + i;
      HttpServer candidateServer = HttpServer.create();
      try {
        candidateServer.bind(new InetSocketAddress(f.bind, candidate), 64);
        return candidateServer;
      } catch (IOException | RuntimeException error) {
        candidateServer.stop(0);
        if (!(error instanceof BindException) || i == attempts - 1) throw error;
      }
    }
    throw new AssertionError("No HTTP bind attempt.");
  }

  public boolean running() {
    return listening && settings.features.web;
  }

  public WebAddresses addresses() {
    return addresses;
  }

  public String state() {
    return state;
  }

  public String failure() {
    return failure;
  }

  public String diagnostic() {
    return "state="
        + state
        + ", bind="
        + settings.features.bind
        + ", port="
        + addresses.port()
        + (running() ? ", base=" + addresses.base(null, null) : "")
        + (failure.isEmpty() ? "" : ", error=" + failure);
  }

  public int sessions() {
    return sessions.count();
  }

  public void reload(PluginSettings settings) {
    this.settings = settings;
    sessions.reload(settings.features);
  }

  public String open(UUID owner, String player, String group) {
    return open(owner, player, group, null);
  }

  public String open(UUID owner, String player, String group, InetAddress peer) {
    return open(owner, player, group, peer, false, false);
  }

  public String open(
      UUID owner,
      String player,
      String group,
      InetAddress peer,
      boolean design,
      boolean preferences) {
    if (!running()) throw new IllegalStateException("Web editor is unavailable.");
    String base = addresses.base(owner, peer);
    SessionRegistry.Opened opened = sessions.open(owner, player, group, WebAddresses.origin(base));
    opened.session.design = design;
    opened.session.preferences = preferences;
    audit.record("web-session-opened", Values.of("player", player, "group", group));
    return base + "/editor#" + opened.token;
  }

  public String openWorkspace(UUID owner,String player,String group,InetAddress peer,boolean design,boolean preferences,boolean prefixes){
    if(!running())throw new IllegalStateException("Web editor is unavailable.");String base=addresses.base(owner,peer);SessionRegistry.Opened opened=sessions.open(owner,player,group,WebAddresses.origin(base));opened.session.prefixes=prefixes;opened.session.design=design;opened.session.preferences=preferences;audit.record("display-session-opened",Values.of("player",player,"group",group));return base+"/studio#"+opened.token;
  }

  public String openStudio(
      UUID owner, String player, InetAddress peer, boolean design, boolean preferences) {
    if (!running()) throw new IllegalStateException("Web editor is unavailable.");
    String base = addresses.base(owner, peer);
    SessionRegistry.Opened opened =
        sessions.open(owner, player, "default", WebAddresses.origin(base));
    opened.session.prefixes = false;
    opened.session.design = design;
    opened.session.preferences = preferences;
    audit.record("display-session-opened", Values.of("player", player));
    return base + "/studio#" + opened.token;
  }

  public CompletableFuture<Void> cleanup() {
    return imageWorker.submit(
        () -> {
          Path root = settings.tempDirectory.resolve("sessions");
          if (!Files.exists(root)) return null;
          Set<UUID> active = sessions.active();
          try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
              UUID id;
              try {
                id = UUID.fromString(folder.getFileName().toString());
              } catch (IllegalArgumentException ignored) {
                continue;
              }
              if (!active.contains(id)) AtomicFiles.deleteTree(folder);
            }
          }
          return null;
        });
  }

  private void handle(HttpExchange exchange) {
    try {
      security(exchange);
      String path = exchange.getRequestURI().getRawPath(), method = exchange.getRequestMethod();
      if (path.startsWith("/packs/")) {
        pack(exchange, path, method);
        return;
      }
      if ("/health".equals(path)) {
        requireMethod(method, "GET");
        reply(
            exchange,
            200,
            "application/json",
            ("{\"status\":\"" + (running() ? "ok" : "disabled") + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (!settings.features.web) throw new HttpProblem(503, "Web editor is disabled.");
      if (path.equals("/studio") || path.equals("/studio.html")) {
        requireMethod(method, "GET");
        reply(exchange, 200, "text/html; charset=utf-8", staticFiles.get("studio.html"));
        return;
      }
      if (path.equals("/") || path.equals("/editor") || path.equals("/editor.html")) {
        requireMethod(method, "GET");
        reply(exchange, 200, "text/html; charset=utf-8", staticFiles.get("editor.html"));
        return;
      }
      if (path.equals("/editor.css")
          || path.equals("/editor.js")
          || path.equals("/studio.css")
          || path.equals("/studio.js")
          || path.equals("/live-save.js")
          || path.equals("/dream.css")
          || path.equals("/dream.js")
          || path.equals("/workbench.css")
          || path.equals("/workbench.js")
          || path.equals("/polish.js")
          || path.equals("/polish.css")
          || path.equals("/artwork.js")
          || path.equals("/artwork.css") || path.equals("/atelier.css") || path.equals("/atelier.js") || path.equals("/studio-font.ttf") || path.equals("/evolution.js") || path.equals("/evolution.css") || path.equals("/composition.js") || path.equals("/composition.css")) {
        requireMethod(method, "GET");
        reply(
            exchange,
            200,
            path.endsWith(".css")
                ? "text/css; charset=utf-8"
                : path.endsWith(".ttf") ? "font/ttf" : "application/javascript; charset=utf-8",
            staticFiles.get(path.substring(1)));
        return;
      }
      if (!path.startsWith("/api/")) throw new HttpProblem(404, "Not found.");
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      EditorSession session =
          sessions.require(
              authorization != null && authorization.startsWith("Bearer ")
                  ? authorization.substring(7)
                  : null);
      boolean mutation = method.equals("POST");
      boolean write =
          mutation && !path.equals("/api/display/preview") && !path.equals("/api/prefix/preview") && !path.equals("/api/text-preview") && !path.equals("/api/preview") && !path.equals("/api/display/artwork");
      if (!session.permit(System.currentTimeMillis(), write,path.equals("/api/display/design")||path.equals("/api/preferences")||path.equals("/api/workspace/preferences")||path.equals("/api/prefix/apply")||path.equals("/api/prefix/preview")))
        throw new HttpProblem(429, "Request limit reached. Wait a minute.");
      boolean locked=write&&!path.equals("/api/prefix/apply")&&!path.equals("/api/display/design")&&!path.equals("/api/preferences")&&!path.equals("/api/workspace/preferences");
      if (mutation) {
        String supplied = exchange.getRequestHeaders().getFirst("Origin");
        if (supplied != null && !supplied.equals(session.origin))
          throw new HttpProblem(403, "Origin is not allowed.");
        if (locked && !session.busy.compareAndSet(false, true))
          throw new HttpProblem(409, "Another editor operation is running.");
      }
      try {
        api(exchange, path, method, session);
      } finally {
        if (locked) session.busy.set(false);
      }
    } catch (Exception | LinkageError error) {
      Throwable root = Failures.root(error);
      int status =
          root instanceof HttpProblem
              ? ((HttpProblem) root).status
              : root instanceof IllegalArgumentException
                      || root instanceof javax.imageio.IIOException
                  ? 400
                  : root instanceof RejectedExecutionException
                      ? 429
                      : root instanceof TimeoutException ? 503 : 500;
      String message =
          status == 500
              ? "Operation failed. Try again or check the server log."
              : Failures.message(root);
      if (status == 500)
        logger.log(java.util.logging.Level.WARNING, "Web editor operation failed.", root);
      try {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        json(exchange, status, json);
      } catch (IOException ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  private JsonObject sessionDocument(EditorSession session) throws IOException {
      JsonObject json = new JsonObject();
      json.addProperty("player", session.player);
      json.addProperty("owner", session.owner.toString());
      json.addProperty("group", session.group);
      json.addProperty("expiresAt", session.expiresAt);
      json.addProperty("language", settings.features.language);
      json.addProperty("canDesign", session.design);
      json.addProperty("canSettings", session.preferences);
      json.addProperty("canPrefixes", session.prefixes);
      json.addProperty("imagesEnabled", settings.features.images);
      json.addProperty("minecraftVersion", settings.minecraft.toString());
      json.addProperty("bitmapFontsSupported", settings.minecraft.bitmapFonts());
      json.addProperty("nativeCanvasSupported", settings.minecraft.bitmapFonts());
      json.addProperty("hdHudSupported", me.snowsun.tabprefix.infrastructure.resourcepack.HudShader.supported(settings.minecraft));
      json.addProperty("hdLegacyTextures", !settings.minecraft.bitmapFonts());
      json.addProperty("canvasOrigin", "ACTION_BAR_CENTER");
      json.addProperty("legacyFont", !settings.minecraft.bitmapFonts());
      json.addProperty(
          "blankScoresSupported",
          settings.minecraft.compareTo(MinecraftVersion.parse("1.20.3")) >= 0);
      json.addProperty("rgbSupported", settings.minecraft.rgb());
      json.addProperty("teamTextLimit", settings.minecraft.teamTextLimit());
      json.addProperty("codesEnabled", settings.features.codes);json.addProperty("autoPrefixEnabled",prefixAccess!=null);
      json.addProperty(
          "maxBytes", Math.min(settings.features.uploadBytes, settings.features.requestBytes));
      json.addProperty("maxFrames", settings.features.maxFrames);
      json.addProperty("cellWidth", settings.features.cellWidth);
      json.addProperty("cellHeight", settings.features.cellHeight);
      json.addProperty("renderHeight", settings.features.renderHeight);
      json.addProperty("ascent", settings.features.ascent);
      json.addProperty("upscaleEnabled", settings.features.upscale);
      json.addProperty("maxScale", settings.features.maxScale);
      JsonArray formats = new JsonArray();
      for (String f : settings.features.formats) formats.add(f);
      json.add("formats", formats);
      GroupTextPrefix text = texts.find(session.group);
      json.addProperty("text", text == null ? "" : text.text());
      json.addProperty("format", text == null ? "MINIMESSAGE" : text.format().name());
      AssetDescriptor asset = graphics.snapshot().asset(session.group);
      json.addProperty("hasImage", asset != null);
      if (asset != null) {
        json.addProperty("renderHeight", asset.renderHeight);
        json.addProperty("ascent", asset.ascent);
        json.addProperty("frames", asset.frames());
        json.addProperty("sourceFormat", asset.sourceFormat);
        JsonArray delays = new JsonArray();
        for (int delay : asset.delays()) delays.add(delay * 50);
        json.add("delays", delays);
        json.add("options", media.options(asset));
      }

    json.addProperty("workspaceId", Digests.sha256(settings.databaseFile.toAbsolutePath().normalize().toString()));
    return json;
  }

  private static final class Catalog {
    final GraphicSnapshot graphics; final String styles, revision; final JsonArray entries;
    Catalog(GraphicSnapshot graphics, String styles, JsonArray entries) {
      this.graphics = graphics; this.styles = styles; this.entries = entries;
      revision = Digests.sha256(entries.toString());
    }
  }
  private Catalog catalog;
  private synchronized Catalog assetCatalog(GraphicSnapshot snapshot, DisplayService.Snapshot display) {
    String styles = display == null ? "" : display.prefixDocument;
    if (catalog != null && catalog.graphics == snapshot && catalog.styles.equals(styles)) return catalog;
    JsonArray list = new JsonArray(); List<AssetDescriptor> assets = new ArrayList<>(snapshot.assets.values());
    assets.sort(Comparator.comparing(a -> a.id.toString()));
    for (AssetDescriptor asset : assets) if (asset.assigned()) {
      JsonObject j = new JsonObject();
      j.addProperty("id", asset.id.toString()); j.addProperty("frames", asset.frames());
      j.addProperty("glyph", String.valueOf((char) asset.glyphCode(0)));
      JsonArray codes=new JsonArray();for(int code:asset.glyphs())codes.add(code);j.add("glyphs",codes);JsonArray delays=new JsonArray();for(int delay:asset.delays())delays.add(delay);j.add("delays",delays);
      j.addProperty("pixelWidth", asset.width); j.addProperty("pixelHeight", asset.height);
      j.addProperty("renderHeight", asset.renderHeight); j.addProperty("ascent", asset.ascent);
      Set<String> targets = new LinkedHashSet<>();
      for (Map.Entry<String, GraphicPrefix> item : snapshot.prefixes.entrySet())
        if (asset.id.equals(item.getValue().assetId)) targets.add(item.getKey());
      if (display != null) for (DisplayDesign.GroupStyle style : display.design.groupStyles)
        if (style.prefixMode.equals("CUSTOM") && asset.id.equals(style.prefixImage)
            || asset.id.equals(style.suffixImage)
            || style.prefixParts.stream().anyMatch(part -> asset.id.equals(part.image))
            || style.suffixParts.stream().anyMatch(part -> asset.id.equals(part.image))) targets.addAll(style.groups);
      JsonArray groups = new JsonArray(); for (String group : targets) groups.add(group);
      j.add("groups", groups); list.add(j);
    }
    return catalog = new Catalog(snapshot, styles, list);
  }
  private JsonObject packDocument() {
    PackRevision pack = packs.active(); JsonObject j = new JsonObject();
    j.addProperty("format", me.snowsun.tabprefix.domain.ResourcePackFormat.forVersion(settings.minecraft).toString());
    j.addProperty("legacy", !settings.minecraft.bitmapFonts());
    j.addProperty("fontCached", new me.snowsun.tabprefix.infrastructure.resourcepack.LegacyFontAssets(settings.dataDirectory.resolve("legacy-font")).cached());
    j.addProperty("error", packs.legacyError()); j.addProperty("building", packs.building());
    if (pack != null) {
      j.addProperty("hash", pack.hash); j.addProperty("glyphs", pack.glyphs);
      j.addProperty("pages", pack.atlases); j.addProperty("hudProfiles", pack.hud.profiles.size());
    }
    return j;
  }
  private CompletableFuture<JsonArray> catalogGroups; private long groupsRequested;
  private synchronized CompletableFuture<JsonArray> workspaceGroups(boolean force) {
    long now = System.currentTimeMillis();
    if (catalogGroups == null || catalogGroups.isCompletedExceptionally()
        || catalogGroups.isDone() && (force || now - groupsRequested >= 1000)) {
      groupsRequested = now; catalogGroups = displayGroups.get();
    }
    return catalogGroups;
  }
  private void workspace(HttpExchange e, String method, EditorSession session) throws Exception {
    requireMethod(method, "GET"); valid(session);
    String view = query(e, "view"); if (view == null) view = "full";
    if (!Arrays.asList("full", "studio", "prefix").contains(view)) throw new IllegalArgumentException("Invalid workspace view");
    boolean design = !view.equals("prefix") && session.design;
    boolean prefs = !view.equals("prefix") && session.preferences;
    boolean prefix = !view.equals("studio") && session.prefixes && prefixAccess != null;
    if (design) displayAccess(session, true); if (prefs) displayAccess(session, false); if (prefix) prefixAllowed(session);
    JsonArray groups = design ? await(workspaceGroups(query(e,"groups")==null)) : null; Map<String,String> known = new HashMap<>();
    for (String key : Arrays.asList("display", "preferences", "prefix", "assets", "groups", "pack")) {
      String value = query(e, key);
      if (value != null && !value.matches("[a-f0-9]{1,64}")) throw new IllegalArgumentException("Invalid workspace revision");
      known.put(key, value);
    }
    JsonObject result = await(texts.synchronize(() -> {
      CompletableFuture<JsonObject> value = new CompletableFuture<>();
      try {
        JsonObject document = new JsonObject(), versions = new JsonObject();
        document.add("session", sessionDocument(session)); document.addProperty("scannedAt", System.currentTimeMillis());
        DisplayService.Snapshot display = displays == null ? null : displays.snapshot(); GraphicSnapshot graphic = graphics.snapshot();
        if (design) {
          String revision = String.valueOf(display.revision); versions.addProperty("display", revision);
          if (!revision.equals(known.get("display"))) document.add("display", display.json());
          Catalog assets = assetCatalog(graphic, display); versions.addProperty("assets", assets.revision);
          if (!assets.revision.equals(known.get("assets"))) document.add("assets", assets.entries);
          String groupRevision = Digests.sha256(groups.toString()); versions.addProperty("groups", groupRevision);
          if (!groupRevision.equals(known.get("groups"))) document.add("groups", groups);
        }
        if (prefs) {
          JsonObject preference = displays.preferences(session.owner).json(); String revision = Digests.sha256(preference.toString());
          versions.addProperty("preferences", revision);
          if (!revision.equals(known.get("preferences"))) document.add("preferences", preference);
        }
        if (prefix) {
          GroupTextPrefix text = texts.find(session.group);
          String revision = PrefixEdit.revision(session.group, graphic, text, display == null ? null : display.design);
          versions.addProperty("prefix", revision);
          if (!revision.equals(known.get("prefix"))) {
            JsonObject part = new JsonObject(); part.addProperty("revision", revision);
            part.add("document", prefixDocument(session, graphic, text, display == null ? null : display.design)); document.add("prefix", part);
          }
        }
        JsonObject pack = packDocument(); String packRevision = Digests.sha256(pack.toString()); versions.addProperty("pack", packRevision);
        if (!packRevision.equals(known.get("pack"))) document.add("pack", pack);
        document.add("versions", versions); value.complete(document);
      } catch (Exception error) { value.completeExceptionally(error); }
      return value;
    }));
    json(e, 200, result);
  }

  private void api(HttpExchange e, String path, String method, EditorSession session)
      throws Exception {
    if (path.equals("/api/workspace")) { workspace(e, method, session); return; }
    if (path.startsWith("/api/display/") || path.equals("/api/preferences") || path.equals("/api/workspace/preferences")) {
      displayApi(e, path, method, session);
      return;
    }
    if (!path.equals("/api/session") && !session.prefixes)
      throw new HttpProblem(403, "This session is for display settings only.");
    if(path.startsWith("/api/prefix/")){prefixApi(e,path,method,session);return;}
    if (path.equals("/api/session")) {
      requireMethod(method, "GET");
      json(e, 200, sessionDocument(session));
      return;
    }
    if (path.equals("/api/upload")) {
      requireMethod(method, "POST");
      if (!settings.features.images) throw new HttpProblem(400, "Images are disabled.");
      ProcessedMedia preview =
          await(
              imageWorker.submit(
                  () -> {
                    byte[] source =
                        body(
                            e,
                            Math.min(
                                settings.features.uploadBytes, settings.features.requestBytes));
                    EditorOptions options =
                        EditorOptions.defaults(
                            settings.features.renderHeight, settings.features.ascent);
                    ProcessedMedia processed =
                        new ImageDecoder().decode(source, options, settings.features);
                    valid(session);
                    Path upload =
                        settings
                            .tempDirectory
                            .resolve("sessions")
                            .resolve(session.id.toString())
                            .resolve("upload.bin");
                    AtomicFiles.write(upload, source);
                    session.upload = upload;session.uploadSource="upload:"+UUID.randomUUID();session.preparedKey=null;session.preparedAsset=null;session.previewSource=session.uploadSource;session.previewOptions=options;
                    session.uploadFormat = processed.format;
                    session.preview = processed;
                    AssetDescriptor stored=media.store(source,processed,options);boolean existing=graphics.snapshot().assets.containsKey(stored.id);
                    try{await(graphics.register(stored));}catch(Exception error){if(!existing)media.delete(stored.id);throw error;}
                    session.ownedAssets.add(stored.id);session.preparedAsset=stored.id;session.previewSource=stored.id.toString();
                    session.preparedImages.put(stored.id+"|"+new Gson().toJson(options),stored.id);
                    return processed;
                  }));
      audit.record(
          "image-uploaded",
          Values.of(
              "player", session.player, "file", preview.format, "frames", preview.frames.size()));
      if (preview.frames.size() > 1)
        audit.record(
            "animation-processed",
            Values.of("player", session.player, "frames", preview.frames.size()));
      JsonObject result = preview(preview);
      result.addProperty("source",session.previewSource);
      result.addProperty("renderHeight", settings.features.renderHeight);
      result.addProperty("ascent", settings.features.ascent);
      json(e, 200, result);
      return;
    }
    if (path.equals("/api/original")) {
      requireMethod(method, "GET");
      String requested=query(e,"source");byte[] source = source(session,requested);
      String format = requested!=null&&!requested.startsWith("upload:")?sourceAsset(session,requested).sourceFormat:session.uploadFormat;
      AssetDescriptor asset = graphics.snapshot().asset(session.group);
      if (format == null && asset != null) format = asset.sourceFormat;
      reply(e, 200, "image/" + ("jpg".equals(format) ? "jpeg" : format), source);
      return;
    }
    if (path.startsWith("/api/frame/")) {
      requireMethod(method, "GET");
      int index;
      try {
        index = Integer.parseInt(path.substring(11));
      } catch (NumberFormatException error) {
        throw new HttpProblem(404, "Frame not found.");
      }
      String requested=query(e,"source");ProcessedMedia preview = session.preview;
      if(requested!=null&&!requested.equals(session.previewSource))preview=null;
      BufferedImage frame;
      if (preview != null) {
        if (index < 0 || index >= preview.frames.size())
          throw new HttpProblem(404, "Frame not found.");
        frame = preview.frames.get(index);
      } else {
        AssetDescriptor asset = requested==null?graphics.snapshot().asset(session.group):sourceAsset(session,requested);
        if (asset == null || index < 0 || index >= asset.frames())
          throw new HttpProblem(404, "Frame not found.");
        reply(e, 200, "image/png", media.frameBytes(asset, index));
        return;
      }
      ByteArrayOutputStream png = new ByteArrayOutputStream();
      ImageIO.write(frame, "png", png);
      reply(e, 200, "image/png", png.toByteArray());
      return;
    }
    if (path.equals("/api/preview")) {
      requireMethod(method, "POST");
      JsonObject request=jsonBody(e);EditorOptions options=options(request);String requested=string(request,"source",defaultSource(session));
      ProcessedMedia result =
          await(
              imageWorker.submit(
                  () -> {
                    ProcessedMedia decoded =
                        new ImageDecoder().decode(source(session,requested), options, settings.features);
                    valid(session);
                    session.preview = decoded;session.previewOptions=options;
                    AssetDescriptor stored=media.store(source(session,requested),decoded,options);boolean existing=graphics.snapshot().assets.containsKey(stored.id);
                    try{await(graphics.register(stored));}catch(Exception error){if(!existing)media.delete(stored.id);throw error;}
                    session.ownedAssets.add(stored.id);session.previewSource=stored.id.toString();session.preparedImages.put(requested+"|"+new Gson().toJson(options),stored.id);
                    return decoded;
                  }));
      JsonObject view=preview(result);view.addProperty("source",session.previewSource);json(e,200,view);
      return;
    }
    if (path.equals("/api/text-preview")) {
      requireMethod(method, "POST");
      JsonObject body = jsonBody(e);
      String text = string(body, "text", "");
      TextFormat format = TextFormat.valueOf(string(body, "format", "MINIMESSAGE"));
      if (!text.isEmpty())
        new GroupTextPrefix(session.group, text, format, session.owner, System.currentTimeMillis());
      JsonObject result = new JsonObject();
      result.add(
          "component",
          new JsonParser()
              .parse(
                  GsonComponentSerializer.gson()
                      .serialize(new TextRenderer(settings).prefixComponent(text, format))));
      json(e, 200, result);
      return;
    }
    if (path.equals("/api/save")) {
      requireMethod(method, "POST");
      if (!settings.features.codes) throw new HttpProblem(400, "Save codes are disabled.");
      JsonObject body = jsonBody(e);
      boolean image = bool(body, "image", false), override = bool(body, "textOverride", false);
      String text = override ? string(body, "text", "") : null;
      TextFormat format = TextFormat.valueOf(string(body, "format", "MINIMESSAGE"));
      if (text != null) {
        new GroupTextPrefix(session.group, text, format, session.owner, System.currentTimeMillis());
        new TextRenderer(settings).prefixComponent(text, format);
      }
      if (!image && text == null) throw new HttpProblem(400, "Choose an image or a text prefix.");
      EditorOptions options =
          options(body.has("options") ? body.getAsJsonObject("options") : new JsonObject());
      GraphicService.SaveResult result =
          await(
              imageWorker.submit(
                  () -> {
                    UUID asset = null;
                    if (image) {
                      byte[] source = source(session);
                      ProcessedMedia processed =
                          new ImageDecoder().decode(source, options, settings.features);
                      valid(session);
                      AssetDescriptor stored = media.store(source, processed, options);
                      try {
                        await(graphics.register(stored));
                        asset = stored.id;
                      } catch (Exception error) {
                        media.delete(stored.id);
                        throw error;
                      }
                    }
                    valid(session);
                    return await(graphics.save(session.owner, session.group, asset, text, format));
                  }));
      audit.record("draft-saved", Values.of("player", session.player, "group", session.group));
      audit.record("save-code-created", Values.of("player", session.player));
      JsonObject saved = new JsonObject();
      saved.addProperty("code", result.code);
      saved.addProperty("command", "/lptab webpref " + result.code);
      saved.addProperty("expiresAt", result.expiresAt);
      json(e, 200, saved);
      return;
    }
    throw new HttpProblem(404, "API route not found.");
  }

  private void prefixAllowed(EditorSession session) throws Exception {
    valid(session);
    if(!session.prefixes||prefixAccess==null||!await(prefixAccess.apply(session.owner,session.group)))throw new HttpProblem(403,"Player is offline, the group changed, or permission to edit this prefix was revoked.");
  }

  private DisplayDesign prefixDesign(){return displays==null?null:displays.snapshot().design;}
  private String prefixRevision(EditorSession session){return PrefixEdit.revision(session.group,graphics.snapshot(),texts.find(session.group),prefixDesign());}

  private JsonObject prefixDocument(EditorSession session,GraphicSnapshot snapshot,GroupTextPrefix stored,DisplayDesign design) throws IOException {
    GroupTextPrefix text=PrefixEdit.text(session.group,stored,design);
    UUID id=PrefixEdit.image(session.group,snapshot,design);AssetDescriptor asset=id==null?null:snapshot.assets.get(id);
    if(asset!=null){session.ownedAssets.add(asset.id);if(session.openedAsset==null)session.openedAsset=asset.id;}
    JsonObject doc=new JsonObject();doc.addProperty("image",asset!=null);doc.addProperty("textOverride",text!=null);
    doc.addProperty("text",text==null?"":text.text());doc.addProperty("format",text==null?"MINIMESSAGE":text.format().name());
    DisplayDesign.GroupStyle rule=design==null?null:design.groupStyle(session.group);doc.addProperty("mode",rule==null?"INHERIT":rule.prefixMode);
    String source=asset==null?"":asset.id.toString();

    doc.addProperty("source",source);doc.add("options",asset==null?new Gson().toJsonTree(EditorOptions.defaults(settings.features.renderHeight,settings.features.ascent)):media.options(asset));
    if(asset!=null){doc.addProperty("pixelWidth",asset.width);doc.addProperty("pixelHeight",asset.height);doc.addProperty("frames",asset.frames());JsonArray delays=new JsonArray();for(int delay:asset.delays())delays.add(delay*50);doc.add("delays",delays);doc.addProperty("sourceFormat",asset.sourceFormat);}
    JsonArray prefixParts=new JsonArray(),suffixParts=new JsonArray();
    if(rule!=null){for(DisplayDesign.AffixPart p:rule.prefixParts)prefixParts.add(affixDocument(session,p));for(DisplayDesign.AffixPart p:rule.suffixParts)suffixParts.add(affixDocument(session,p));}
    doc.add("prefixParts",prefixParts);doc.add("suffixParts",suffixParts);
    JsonObject suffix=imageDocument(session,rule==null?null:rule.suffixImage);
    suffix.addProperty("id","main-suffix");suffix.addProperty("enabled",true);suffix.addProperty("text",rule==null?"":rule.suffix.source(0,false));
    suffix.addProperty("textOverride",rule!=null&&rule.suffixMode.equals("CUSTOM"));suffix.addProperty("mode",rule==null?"LUCKPERMS":rule.suffixMode);suffix.addProperty("format",rule==null?"MINIMESSAGE":rule.suffixFormat.name());suffix.add("line",rule==null?new DisplayDesign.Line(new JsonObject()).json():rule.suffix.json());doc.add("suffix",suffix);
    return doc;
  }

  private JsonObject imageDocument(EditorSession session,UUID id)throws IOException {
    AssetDescriptor a=id==null?null:graphics.snapshot().assets.get(id);JsonObject doc=new JsonObject();doc.addProperty("image",a!=null);doc.addProperty("source",a==null?"":a.id.toString());
    doc.add("options",a==null?new Gson().toJsonTree(EditorOptions.defaults(settings.features.renderHeight,settings.features.ascent)):media.options(a));
    if(a!=null){session.ownedAssets.add(a.id);doc.addProperty("pixelWidth",a.width);doc.addProperty("pixelHeight",a.height);doc.addProperty("frames",a.frames());doc.addProperty("sourceFormat",a.sourceFormat);JsonArray delays=new JsonArray();for(int delay:a.delays())delays.add(delay*50);doc.add("delays",delays);}
    return doc;
  }
  private JsonObject affixDocument(EditorSession session,DisplayDesign.AffixPart p)throws IOException {
    JsonObject doc=imageDocument(session,p.image);doc.addProperty("id",p.id);doc.addProperty("enabled",p.enabled);doc.addProperty("gap",p.gap);doc.addProperty("format",p.format.name());doc.addProperty("text",p.line.source(0,false));doc.addProperty("textOverride",!p.line.text.isEmpty()||!p.line.frames.isEmpty());doc.add("line",p.line.json());return doc;
  }
  private UUID prepareDecorationImage(EditorSession session,JsonObject doc)throws Exception {
    if(!bool(doc,"image",false))return null;String source=string(doc,"source","");EditorOptions options=options(DisplayDesign.object(doc.get("options"),"options"));
    UUID reusable=reusablePrefixImage(session,source,options);return reusable!=null?reusable:await(imageWorker.submit(()->preparePrefixImage(session,source,options)));
  }
  private JsonObject decorationLine(JsonObject doc){
    JsonObject line=doc.has("line")?new JsonParser().parse(doc.get("line").toString()).getAsJsonObject():new JsonObject();String text=bool(doc,"textOverride",false)?DisplayDesign.string(doc,"text","",512):"";
    line.addProperty("text",text);if("FRAMES".equals(DisplayDesign.string(line,"animation","NONE",16))){JsonArray frames=line.has("frames")?line.getAsJsonArray("frames"):new JsonArray();JsonArray next=new JsonArray();next.add(text);for(int n=1;n<frames.size();n++)next.add(frames.get(n));line.add("frames",next);}
    DisplayDesign.Line checked = new DisplayDesign.Line(line);
    boolean legacy = "LEGACY".equals(string(doc,"format","MINIMESSAGE"));
    TextRenderer renderer = new TextRenderer(settings);
    validateDisplaySource(renderer, Collections.emptyMap(), null, checked.text, legacy);
    for (String frame : checked.frames)
      validateDisplaySource(renderer, Collections.emptyMap(), null, frame, legacy);
    return checked.json();
  }
  private PrefixDecoration decorations(EditorSession session,JsonObject doc)throws Exception {
    JsonObject change=new JsonObject();
    for(String key:new String[]{"prefixParts","suffixParts"})if(doc.has(key)){
      JsonArray input=doc.getAsJsonArray(key),out=new JsonArray();if(input.size()>32)throw new IllegalArgumentException("Up to 32 decorations per side");
      for(JsonElement e:input){JsonObject item=DisplayDesign.object(e,key),part=new JsonObject();part.addProperty("id",DisplayDesign.string(item,"id","",48));part.addProperty("enabled",bool(item,"enabled",true));part.addProperty("gap",DisplayDesign.integer(item,"gap",1,0,8));part.addProperty("format",DisplayDesign.choice(item,"format","MINIMESSAGE","MINIMESSAGE","LEGACY"));part.add("line",decorationLine(item));
        UUID image=prepareDecorationImage(session,item);part.addProperty("image",image==null?"":image.toString());out.add(new DisplayDesign.AffixPart(part).json());}
      change.add(key,out);
    }
    if(doc.has("suffix")){JsonObject suffix=DisplayDesign.object(doc.get("suffix"),"suffix");String mode=bool(suffix,"textOverride",false)?"CUSTOM":DisplayDesign.choice(suffix,"mode","LUCKPERMS","CUSTOM","LUCKPERMS","NONE");change.addProperty("suffixMode",mode);change.addProperty("suffixFormat",DisplayDesign.choice(suffix,"format","MINIMESSAGE","MINIMESSAGE","LEGACY"));change.add("suffix",decorationLine(suffix));UUID image=prepareDecorationImage(session,suffix);change.addProperty("suffixImage",image==null?"":image.toString());}
    return change.size()==0?null:new PrefixDecoration(change);
  }

  private JsonObject prefixState(EditorSession session,String known) throws Exception {
    return await(texts.synchronize(()->{
      CompletableFuture<JsonObject> future=new CompletableFuture<>();
      try{GraphicSnapshot snapshot=graphics.snapshot();GroupTextPrefix stored=texts.find(session.group);DisplayDesign design=prefixDesign();
        String revision=PrefixEdit.revision(session.group,snapshot,stored,design);JsonObject result=new JsonObject();result.addProperty("revision",revision);
        boolean changed=!revision.equals(known);result.addProperty("changed",changed);if(changed)result.add("document",prefixDocument(session,snapshot,stored,design));future.complete(result);
      }catch(Exception error){future.completeExceptionally(error);}return future;
    }));
  }

  private void prefixApi(HttpExchange e,String path,String method,EditorSession session) throws Exception {
    prefixAllowed(session);
    if(path.equals("/api/prefix/state")){
      requireMethod(method,"GET");json(e,200,prefixState(session,query(e,"revision")));return;
    }
    if(path.equals("/api/prefix/preview")){requireMethod(method,"POST");JsonObject request=jsonBody(e),doc=DisplayDesign.object(request.get("document"),"document");JsonObject result=new JsonObject();JsonArray prefix=new JsonArray(),suffix=new JsonArray();prefix.add(decorationPreview(session,doc,"main-prefix"));if(doc.has("suffix"))suffix.add(decorationPreview(session,doc.getAsJsonObject("suffix"),"main-suffix"));for(String key:new String[]{"prefixParts","suffixParts"})if(doc.has(key)){JsonArray parts=doc.getAsJsonArray(key);if(parts.size()>32)throw new IllegalArgumentException("Up to 32 decorations per side");for(JsonElement part:parts)(key.equals("prefixParts")?prefix:suffix).add(decorationPreview(session,part.getAsJsonObject(),string(part.getAsJsonObject(),"id","")));}result.add("prefix",prefix);result.add("suffix",suffix);json(e,200,result);return;}
    if(!path.equals("/api/prefix/apply"))throw new HttpProblem(404,"Prefix route not found");
    requireMethod(method,"POST");JsonObject request=jsonBody(e),doc=DisplayDesign.object(request.get("document"),"document");
    String expected=string(request,"revision","");if(!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid prefix revision");
    boolean image=bool(doc,"image",false),override=bool(doc,"textOverride",false);String text=override?string(doc,"text",""):null;
    TextFormat format=TextFormat.valueOf(string(doc,"format","MINIMESSAGE"));
    if(text!=null){new GroupTextPrefix(session.group,text,format,session.owner,System.currentTimeMillis());new TextRenderer(settings).prefixComponent(text,format);}
    UUID asset=null;
    if(image){String source=string(doc,"source",defaultSource(session));EditorOptions options=options(DisplayDesign.object(doc.get("options"),"options"));asset=reusablePrefixImage(session,source,options);if(asset==null)asset=await(imageWorker.submit(()->preparePrefixImage(session,source,options)));}
    PrefixDecoration decoration=decorations(session,doc);prefixAllowed(session);
    PrefixEditCommit commit=await(graphics.edit(session.owner,session.group,asset,text,format,expected,decoration,result->{if(displays!=null)displays.acceptPrefixEdit(result);}));
    if(commit.changed){prefixApplied.run();PackRevision active=packs.active();
      Set<UUID> needed=decoration==null?new HashSet<>():decoration.images();if(asset!=null)needed.add(asset);if(settings.features.pack&&settings.features.autoBuild&&needed.stream().anyMatch(id->active==null||!active.assets.contains(id)))packs.rebuild();
      audit.record("prefix-auto-applied",Values.of("player",session.player,"group",session.group));
    }
    JsonObject result=prefixState(session,null);result.addProperty("applied",true);result.addProperty("packBuilding",packs.building());json(e,200,result);
  }

  private JsonObject decorationPreview(EditorSession session,JsonObject doc,String id)throws IOException {
    JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("enabled",bool(doc,"enabled",true));result.addProperty("gap",integer(doc,"gap",1));
    String text=bool(doc,"textOverride",false)?DisplayDesign.string(doc,"text","",512):"";TextFormat format=TextFormat.valueOf(string(doc,"format","MINIMESSAGE"));result.add("component",new JsonParser().parse(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(new TextRenderer(settings).prefixComponent(text,format))));
    if(bool(doc,"image",false)){String source=string(doc,"source","");AssetDescriptor image=sourceAsset(session,source);result.addProperty("source",source);result.addProperty("pixelWidth",image.width);result.addProperty("pixelHeight",image.height);EditorOptions options=options(doc.getAsJsonObject("options"));result.addProperty("renderHeight",options.renderHeight);result.addProperty("ascent",options.ascent);result.addProperty("frames",image.frames());JsonArray delays=new JsonArray();for(int delay:image.delays())delays.add(delay*50);result.add("delays",delays);}
    return result;
  }

  private UUID reusablePrefixImage(EditorSession session,String source,EditorOptions options) throws IOException {
    UUID cached=session.preparedImages.get(source+"|"+new Gson().toJson(options));if(cached!=null&&graphics.snapshot().assets.containsKey(cached))return cached;
    if(!source.startsWith("upload:")){AssetDescriptor asset=sourceAsset(session,source);if(new Gson().toJsonTree(options).equals(media.options(asset)))return asset.id;}
    return null;
  }

  private UUID preparePrefixImage(EditorSession session,String source,EditorOptions options) throws Exception {
    String key=source+"|"+new Gson().toJson(options);UUID reusable=reusablePrefixImage(session,source,options);if(reusable!=null)return reusable;
    if(!settings.features.images)throw new IllegalArgumentException("Image processing is disabled.");
    byte[] original=source(session,source);
    ProcessedMedia processed=source.equals(session.previewSource)&&new Gson().toJsonTree(options).equals(new Gson().toJsonTree(session.previewOptions))?session.preview:null;
    if(processed==null)processed=new ImageDecoder().decode(original,options,settings.features);
    valid(session);AssetDescriptor stored=media.store(original,processed,options);
    boolean existing=graphics.snapshot().assets.containsKey(stored.id);
    try{await(graphics.register(stored));}catch(Exception error){if(!existing)media.delete(stored.id);throw error;}
    session.preparedAsset=stored.id;session.preparedKey=key;session.ownedAssets.add(stored.id);session.preparedImages.put(key,stored.id);return stored.id;
  }

  private static String query(HttpExchange e,String name){
    String raw=e.getRequestURI().getRawQuery();if(raw==null)return null;
    for(String part:raw.split("&")){String[] pair=part.split("=",2);if(pair.length==2&&pair[0].equals(name))try{return URLDecoder.decode(pair[1],"UTF-8");}catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}}
    return null;
  }

  private String defaultSource(EditorSession session){UUID image=PrefixEdit.image(session.group,graphics.snapshot(),prefixDesign());return session.uploadSource!=null?session.uploadSource:image==null?"":image.toString();}
  private AssetDescriptor sourceAsset(EditorSession session,String token){
    UUID id=UUID.fromString(token),current=PrefixEdit.image(session.group,graphics.snapshot(),prefixDesign());
    if(!id.equals(current)&&!id.equals(session.openedAsset)&&!id.equals(session.preparedAsset)&&!session.ownedAssets.contains(id))throw new HttpProblem(403,"Image does not belong to this prefix session");
    AssetDescriptor asset=graphics.snapshot().assets.get(id);if(asset==null)throw new HttpProblem(404,"Image unavailable");return asset;
  }
  private byte[] source(EditorSession session,String requested) throws IOException {
    if(requested==null)return source(session);valid(session);
    if(requested.startsWith("upload:")){if(!requested.equals(session.uploadSource)||session.upload==null)throw new HttpProblem(409,"Uploaded image changed");return Files.readAllBytes(session.upload);}
    return media.source(sourceAsset(session,requested));
  }

  private void displayAccess(EditorSession session, boolean design) throws Exception {
    valid(session);
    if (displays == null || access == null)
      throw new HttpProblem(503, "Display studio is unavailable.");
    if (!(design ? session.design : session.preferences)
        || !await(access.apply(session.owner, design ? "tabprefix.design" : "tabprefix.settings")))
      throw new HttpProblem(
          403, "You must be online with the required permission. Open a new session in Minecraft.");
  }

  private void displayApi(HttpExchange e, String path, String method, EditorSession session)
      throws Exception {
    if (path.equals("/api/display/assets")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      json(e, 200, assetCatalog(graphics.snapshot(), displays.snapshot()).entries);
      return;
    }
    if (path.startsWith("/api/display/asset/")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      UUID id;
      try {
        id = UUID.fromString(path.substring("/api/display/asset/".length()));
      } catch (IllegalArgumentException ex) {
        throw new HttpProblem(404, "Image not found");
      }
      AssetDescriptor asset = graphics.snapshot().assets.get(id);
      if (asset == null || !asset.assigned()) throw new HttpProblem(404, "Image not found");
      String value=query(e,"frame");int frame;try{frame=value==null?0:Integer.parseInt(value);}catch(NumberFormatException ex){throw new HttpProblem(400,"Invalid image frame");}if(frame<0||frame>=asset.frames())throw new HttpProblem(400,"Invalid image frame");byte[] png=media.frameBytes(asset,frame);
      reply(e, 200, "image/png", png);
      return;
    }
    if (path.equals("/api/display/image")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      if (!settings.features.images) throw new HttpProblem(403, "Images disabled");
      byte[] bytes =
          body(e, Math.min(settings.features.uploadBytes, settings.features.requestBytes));
      AssetDescriptor stored =
          await(
              imageWorker.submit(
                  () -> {
                    EditorOptions options =
                        EditorOptions.defaults(
                            settings.features.renderHeight, settings.features.ascent);
                    ProcessedMedia processed =
                        new ImageDecoder().decode(bytes, options, settings.features);
                    valid(session);
                    return media.store(bytes, processed, options);
                  }));
      displayAccess(session, true);
      AssetDescriptor asset = await(graphics.registerScreen(stored));
      packs.rebuild();
      JsonObject j = new JsonObject();
      j.addProperty("id", asset.id.toString());
      j.addProperty("frames", asset.frames());
      j.addProperty("glyph", String.valueOf((char) asset.glyphCode(0)));
      j.add("groups", new JsonArray());
      json(e, 200, j);
      audit.record("screen-image-created", Values.of("player", session.player, "asset", asset.id));
      return;
    }
    if (path.equals("/api/display/font-baseline")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      byte[] bytes = body(e, 65536);
      displayAccess(session, true);
      new me.snowsun.tabprefix.infrastructure.resourcepack.LegacyFontAssets(
              settings.dataDirectory.resolve("legacy-font"))
          .importWidths(bytes);
      packs.rebuild();
      JsonObject j = new JsonObject();
      j.addProperty("saved", true);
      json(e, 200, j);
      return;
    }
    if (path.equals("/api/display/pack") || path.equals("/api/display/pack/rebuild")) {
      displayAccess(session, true);
      PackRevision pack;
      if (path.endsWith("rebuild")) {
        requireMethod(method, "POST");
        pack = await(packs.rebuild());
      } else {
        requireMethod(method, "GET");
        pack = packs.active();
      }
      json(e, 200, packDocument());
      return;
    }
    if (path.equals("/api/display/pack/download")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      PackRevision pack = packs.active();
      if (pack == null) throw new HttpProblem(404, "Build a pack first");
      packs.pin(pack);
      e.getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=TabPrefix-resource-pack.zip");
      pack(e, "/packs/TabPrefix-" + pack.hash + ".zip", "GET");
      return;
    }
    if (path.equals("/api/display/prefix-editor")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      if (!await(access.apply(session.owner, "tabprefix.webeditor")))
        throw new HttpProblem(403, "Prefix editor permission required");
      String group = GroupTextPrefix.normalizeGroup(string(jsonBody(e), "group", session.group));
      if (!group.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid group");
      if (!group.equals(session.group)
          && !await(access.apply(session.owner, "tabprefix.prefix.manage")))
        throw new HttpProblem(403, "Group management permission required");
      JsonObject j = new JsonObject();
      j.addProperty(
          "url", open(session.owner, session.player, group, null, true, session.preferences));
      json(e, 200, j);
      return;
    }
    if (path.equals("/api/display/groups")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      json(e, 200, await(displayGroups.get()));
      return;
    }
    if (path.equals("/api/workspace/preferences")) {
      displayAccess(session, false); requireMethod(method, "POST");
      JsonObject request = jsonBody(e); String revision = string(request, "revision", "");
      if (!revision.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid preferences revision");
      PlayerDisplaySettings candidate = new PlayerDisplaySettings(DisplayDesign.object(request.get("document"), "document"));
      PlayerDisplaySettings saved = await(displays.savePreferences(session.owner, candidate, revision));
      JsonObject result = new JsonObject(); result.add("document", saved.json());
      result.addProperty("revision", Digests.sha256(saved.json().toString())); json(e, 200, result); return;
    }
    if (path.equals("/api/preferences")) {
      displayAccess(session, false);
      if (method.equals("GET")) {
        json(e, 200, displays.preferences(session.owner).json());
        return;
      }
      requireMethod(method, "POST");
      PlayerDisplaySettings candidate = new PlayerDisplaySettings(jsonBody(e));
      displayAccess(session, false);
      json(e, 200, await(displays.savePreferences(session.owner, candidate)).json());
      audit.record("display-preferences-saved", Values.of("player", session.player));
      return;
    }
    if(path.equals("/api/display/state")) {
      displayAccess(session,true);requireMethod(method,"GET");
      String query=e.getRequestURI().getRawQuery();int known=-1;
      if(query!=null) { if(!query.matches("revision=[0-9]{1,10}"))throw new IllegalArgumentException("Invalid revision query");try{known=Integer.parseInt(query.substring(9));}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid revision");} }
      DisplayService.Snapshot current=displays.snapshot();JsonObject state=new JsonObject();state.addProperty("revision",current.revision);state.addProperty("changed",current.revision!=known);
      if(current.revision!=known)state.add("design",current.design.json());
      state.addProperty("packBuilding",packs.building());state.addProperty("packError",packs.legacyError());json(e,200,state);return;
    }
    if (path.equals("/api/display/design")) {
      displayAccess(session, true);
      if (method.equals("GET")) {
        json(e, 200, displays.snapshot().json());
        return;
      }
      requireMethod(method, "POST");
      JsonObject j = jsonBody(e,Math.min(settings.features.requestBytes,1048576));
      DisplayDesign candidate = new DisplayDesign(DisplayDesign.object(j.get("design"), "design"));
      validateDisplay(candidate);
      int revision = DisplayDesign.integer(j, "revision", -1, 1, Integer.MAX_VALUE - 1);
      displayAccess(session, true);
      DisplayService.Snapshot result=await(displays.save(candidate,revision,session.owner));
      JsonObject saved=new JsonObject();saved.addProperty("revision",result.revision);
      if(!DisplayDesign.bool(j,"compact",false)||!candidate.json().equals(j.getAsJsonObject("design")))saved.add("design",result.design.json());
      if (settings.features.pack
          && (candidate.layoutEnabled || candidate.tabLayoutMode.equals("COMPACT")&&(candidate.headerEnabled||candidate.footerEnabled)
              || !candidate.artwork.boxes.isEmpty() || candidate.sidebarEnabled && candidate.sidebarMode.equals("HUD")
              || candidate.screenEnabled
                  && candidate.screen.stream()
                      .anyMatch(row -> row.enabled && row.anchor.equals("FREE_XY"))))
        packs.designChanged();
      json(e, 200, saved);
      audit.record(
          "display-design-saved", Values.of("player", session.player, "revision", result.revision));
      return;
    }
    if(path.equals("/api/display/artwork")) {
      displayAccess(session,true);requireMethod(method,"POST");
      JsonObject j=jsonBody(e,Math.min(settings.features.requestBytes,1048576));
      DisplayDesign d=new DisplayDesign(DisplayDesign.object(j.get("design"),"design"));
      String surface=j.has("surface")?j.get("surface").getAsString():"SIDEBAR";
      int index=DisplayDesign.integer(j,"index",0,0,79);
      if(!Arrays.asList("SIDEBAR","HEADER","FOOTER","CELL").contains(surface))throw new IllegalArgumentException("Invalid artwork surface");
      me.snowsun.tabprefix.domain.DisplayArtwork.Box box=d.artwork.box(surface,index);
      if(!surface.equals("SIDEBAR")&&box==null)throw new IllegalArgumentException("Create a TAB background first");
      ByteArrayOutputStream png=new ByteArrayOutputStream();
      int density=DisplayDesign.integer(j,"density",1,1,4);
      javax.imageio.ImageIO.write(me.snowsun.tabprefix.infrastructure.resourcepack.ArtworkRasterizer.render(d,box,box==null?d.sidebarWidth:box.width,box==null?d.artwork.height(d):box.height,density),"png",png);
      reply(e,200,"image/png",png.toByteArray());return;
    }
    if (path.equals("/api/display/preview")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      JsonObject j = jsonBody(e,Math.min(settings.features.requestBytes,1048576));
      DisplayDesign candidate;
      if(j.has("design")){candidate=new DisplayDesign(DisplayDesign.object(j.get("design"),"design"));validateDisplay(candidate);}
      else { DisplayService.Snapshot current=displays.snapshot();int revision=DisplayDesign.integer(j,"revision",-1,1,Integer.MAX_VALUE-1);if(revision!=current.revision)throw new HttpProblem(409,"Preview revision changed");candidate=current.design; }
      int tick = DisplayDesign.integer(j, "tick", 0, 0, 100000000);
      if (displayPreview == null) throw new HttpProblem(503, "Server preview is unavailable.");
      json(e, 200, await(displayPreview.render(session.owner, candidate, tick)));
      return;
    }
    throw new HttpProblem(404, "Display route not found.");
  }

  private static final class ValidationCache {
    final PluginSettings settings;final java.util.Set<String> sources=java.util.concurrent.ConcurrentHashMap.newKeySet();
    ValidationCache(PluginSettings settings){this.settings=settings;}
  }
  private volatile ValidationCache displayValidation;
  private void validateDisplay(DisplayDesign design) {
    PluginSettings cfg=settings;ValidationCache cache=displayValidation;
    if(cache==null||cache.settings!=cfg)synchronized(this){cache=displayValidation;if(cache==null||cache.settings!=cfg)displayValidation=cache=new ValidationCache(cfg);}
    TextRenderer renderer = new TextRenderer(cfg);
    Map<String, String> vars = new LinkedHashMap<>();
    for (String key : Arrays.asList("player", "group", "server", "world")) vars.put(key, "Example");
    vars.put("prefix", "§6[VIP] ");
    vars.put("suffix", "§6 ★");
    vars.put("display_name", "Example");
    vars.put("tab_name", "Example");
    vars.put("weight", "1");
    for (String key :
        Arrays.asList(
            "online",
            "max",
            "ping",
            "time",
            "date",
            "health",
            "max_health",
            "food",
            "experience",
            "progress",
            "remaining",
            "level",
            "x",
            "y",
            "z",
            "layout_overflow")) vars.put(key, "1");
    List<String> sources = new ArrayList<>();
    sources.add(design.title);
    sources.add(design.nameTagPrefix);
    sources.add(design.nameTagSuffix);
    List<DisplayDesign.Line> lines = new ArrayList<>();
    lines.addAll(design.header);
    lines.addAll(design.footer);
    lines.addAll(design.sidebar);
    lines.add(design.tabPlayerFormat);
    for (DisplayDesign.GroupStyle style : design.groupStyles) {
      validateDisplaySource(renderer, vars, cache, style.suffix.text, style.suffixFormat == TextFormat.LEGACY);
      for (String frame : style.suffix.frames)
        validateDisplaySource(renderer, vars, cache, frame, style.suffixFormat == TextFormat.LEGACY);
      lines.add(style.prefix);
      for (List<DisplayDesign.AffixPart> parts : Arrays.asList(style.prefixParts, style.suffixParts))
        for (DisplayDesign.AffixPart part : parts) {
          validateDisplaySource(renderer, vars, cache, part.line.text, part.format == TextFormat.LEGACY);
          for (String frame : part.line.frames)
            validateDisplaySource(renderer, vars, cache, frame, part.format == TextFormat.LEGACY);
        }
    }
    for (DisplayDesign.Boss bar : design.bossBars) lines.add(bar.line);
    for (DisplayDesign.Screen item : design.screen) lines.add(item.line);
    for (DisplayDesign.Slot slot : design.slots) lines.add(slot.line);
    for (DisplayDesign.Line line : lines) {
      sources.add(line.text);
      sources.addAll(line.frames);
    }
    for (String source : new java.util.LinkedHashSet<>(sources))
      validateDisplaySource(renderer, vars, cache, source, false);
  }

  private void validateDisplaySource(TextRenderer renderer, Map<String, String> vars,
      ValidationCache cache, String source, boolean legacy) {
    String key = (legacy ? "LEGACY:" : "MINI:") + source;
    if (cache != null && cache.sources.contains(key)) return;
    String plain = renderer.plain(legacy ? renderer.displayLegacy(source, vars) : renderer.display(source, vars));
    for (int i = 0; i < plain.length(); i++)
      if (Character.isISOControl(plain.charAt(i)))
        throw new IllegalArgumentException("Each display block must render as one line without control characters.");
    if (cache != null) {
      if (cache.sources.size() >= 2048) cache.sources.clear();
      cache.sources.add(key);
    }
  }

  private byte[] source(EditorSession session) throws IOException {
    valid(session);
    if (session.upload != null) {
      if (Files.size(session.upload) > settings.features.uploadBytes)
        throw new HttpProblem(413, "Original exceeds upload limit.");
      return Files.readAllBytes(session.upload);
    }
    AssetDescriptor asset = graphics.snapshot().asset(session.group);
    if (asset == null) throw new HttpProblem(400, "Upload an image first.");
    return media.source(asset);
  }

  private static void valid(EditorSession session) {
    if (session.revoked || session.expiresAt <= System.currentTimeMillis())
      throw new HttpProblem(401, "Editor session expired.");
  }

  private EditorOptions options(JsonObject j) {
    FeatureSettings f = settings.features;
    return new EditorOptions(
        integer(j, "x", 0),
        integer(j, "y", 0),
        integer(j, "width", 0),
        integer(j, "height", 0),
        EditorOptions.Fit.valueOf(string(j, "fit", "CONTAIN").toUpperCase(Locale.ROOT)),
        bool(j, "pixelArt", true),
        bool(j, "upscale", false),
        bool(j, "flipX", false),
        bool(j, "flipY", false),
        integer(j, "rotation", 0),
        integer(j, "renderHeight", f.renderHeight),
        integer(j, "ascent", f.ascent),
        decimal(j, "brightness", 0),
        decimal(j, "contrast", 1),
        decimal(j, "saturation", 1),integer(j,"canvasWidth",0),integer(j,"canvasHeight",0),integer(j,"anchorX",50),integer(j,"anchorY",50),integer(j,"offsetX",0),integer(j,"offsetY",0));
  }

  private static JsonObject preview(ProcessedMedia p) {
    JsonObject j = new JsonObject();
    j.addProperty("sourceWidth", p.sourceWidth);
    j.addProperty("sourceHeight", p.sourceHeight);
    j.addProperty("frames", p.frames.size());
    j.addProperty("format", p.format);
    JsonArray delays = new JsonArray();
    for (int d : p.delays) delays.add(d * 50);
    j.add("delays", delays);
    return j;
  }

  private void pack(HttpExchange e, String path, String method) throws IOException {
    if (!method.equals("GET") && !method.equals("HEAD"))
      throw new HttpProblem(405, "Expected GET or HEAD.");
    String name = path.substring(7);
    if (name.equals("TabPrefix.zip")) {
      String query = e.getRequestURI().getRawQuery();
      if (query == null || !query.matches("hash=[a-f0-9]{40}"))
        throw new HttpProblem(404, "Pack not found.");
      name = "TabPrefix-" + query.substring(5) + ".zip";
    }
    if (!name.matches("TabPrefix-[a-f0-9]{40}\\.zip"))
      throw new HttpProblem(404, "Pack not found.");
    Path file = settings.packsDirectory.resolve(name);
    if (!settings.features.pack || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new HttpProblem(404, "Pack not found.");
    long size = Files.size(file), start = 0, end = size - 1;
    int status = 200;
    String range = e.getRequestHeaders().getFirst("Range");
    if (range != null) {
      if (!range.matches("bytes=[0-9]*-[0-9]*")) throw range(size, e);
      String[] parts = range.substring(6).split("-", -1);
      try {
        if (parts[0].isEmpty()) {
          long suffix = Long.parseLong(parts[1]);
          if (suffix < 1) throw new NumberFormatException();
          start = Math.max(0, size - suffix);
        } else {
          start = Long.parseLong(parts[0]);
          if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
        }
        if (start < 0 || start >= size || end < start) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        throw range(size, e);
      }
      status = 206;
      e.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
    }
    e.getResponseHeaders().set("Content-Type", "application/zip");
    e.getResponseHeaders()
        .set(
            "Cache-Control",
            e.getRequestURI().getPath().startsWith("/api/")
                ? "private, no-store"
                : "public, max-age=31536000, immutable");
    e.getResponseHeaders().set("ETag", "\"" + name.substring(10, 50) + "\"");
    e.getResponseHeaders().set("Accept-Ranges", "bytes");
    long length = end - start + 1;
    e.getResponseHeaders().set("Content-Length", String.valueOf(length));
    e.sendResponseHeaders(status, method.equals("HEAD") ? -1 : length);
    if (!method.equals("HEAD"))
      try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r");
          OutputStream output = e.getResponseBody()) {
        input.seek(start);
        byte[] buffer = new byte[32768];
        while (length > 0) {
          int n = input.read(buffer, 0, (int) Math.min(buffer.length, length));
          if (n < 0) throw new EOFException();
          output.write(buffer, 0, n);
          length -= n;
        }
      }
  }

  private static HttpProblem range(long size, HttpExchange e) {
    e.getResponseHeaders().set("Content-Range", "bytes */" + size);
    return new HttpProblem(416, "Invalid byte range.");
  }

  private static void security(HttpExchange e) {
    Headers h = e.getResponseHeaders();
    h.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " blob: data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none';"
            + " form-action 'none'");
    h.set("X-Content-Type-Options", "nosniff");
    h.set("Referrer-Policy", "no-referrer");
    h.set("Cache-Control", "no-store");
  }

  private static void requireMethod(String actual, String expected) {
    if (!actual.equals(expected)) throw new HttpProblem(405, "Expected " + expected + ".");
  }

  private static byte[] body(HttpExchange e, long limit) throws IOException {
    String length = e.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        long value = Long.parseLong(length);
        if (value < 0 || value > limit) {
          if (value > limit && value <= limit + 65536) {
            byte[] discard = new byte[8192];
            long remaining = value;
            while (remaining > 0) {
              int n =
                  e.getRequestBody().read(discard, 0, (int) Math.min(remaining, discard.length));
              if (n < 0) break;
              remaining -= n;
            }
          }
          throw new HttpProblem(413, "Request exceeds upload limit.");
        }
      } catch (NumberFormatException error) {
        throw new HttpProblem(400, "Invalid content length.");
      }
    }
    return read(e.getRequestBody(), limit);
  }

  private static byte[] read(InputStream input, long limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if ((long) out.size() + n > limit)
        throw new HttpProblem(413, "Request exceeds upload limit.");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static JsonObject jsonBody(HttpExchange e) throws IOException {
    return jsonBody(e,65536);
  }

  private static JsonObject jsonBody(HttpExchange e,long limit) throws IOException {
    try {
      return new JsonParser()
          .parse(new String(body(e, limit), StandardCharsets.UTF_8))
          .getAsJsonObject();
    } catch (JsonParseException | IllegalStateException error) {
      throw new HttpProblem(400, "Invalid JSON object.");
    }
  }

  private static boolean bool(JsonObject j, String key, boolean fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Expected boolean: " + key);
    return e.getAsBoolean();
  }

  private static String string(JsonObject j, String key, String fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected string: " + key);
    return e.getAsString();
  }

  private static int integer(JsonObject j, String key, int fallback) {
    double n = decimal(j, key, fallback);
    if (n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Expected integer: " + key);
    return (int) n;
  }

  private static double decimal(JsonObject j, String key, double fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected number: " + key);
    double n = e.getAsDouble();
    if (!Double.isFinite(n)) throw new IllegalArgumentException("Expected finite number: " + key);
    return n;
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(30, TimeUnit.SECONDS);
  }

  private static void json(HttpExchange e, int status, JsonElement json) throws IOException {
    reply(
        e,
        status,
        "application/json; charset=utf-8",
        json.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void reply(HttpExchange e, int status, String type, byte[] bytes)
      throws IOException {
    e.getResponseHeaders().set("Content-Type", type);
    e.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    listening = false;
    state = "stopped";
    addresses.clear();
    sessions.close();
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (pool != null) pool.shutdownNow();
    imageWorker.close();
  }
}
