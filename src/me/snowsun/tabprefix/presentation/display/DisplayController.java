package me.snowsun.tabprefix.presentation.display;

import com.google.gson.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.application.DisplayService;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.HeaderFooterAccess;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;

/** All Bukkit reads and display writes run on the main thread. HTTP only requests snapshots. */
public final class DisplayController implements Listener, AutoCloseable {
  private static final class Header {
    final String beforeHeader, beforeFooter;
    String header, footer, sentHeader, sentFooter;
    boolean ownsHeader, ownsFooter;

    Header(Player p, HeaderFooterAccess access) {
      beforeHeader = access.header(p);
      beforeFooter = access.footer(p);
    }
  }

  private final JavaPlugin plugin;
  private final DisplayService service;
  private final PrefixController prefixes;
  private final ScreenHudView screenHud = new ScreenHudView();
  private final SidebarView sidebar;
  private final boolean nativeCanvasSupported;
  private final HeaderFooterAccess headerFooter;
  private final NameTagView tags;
  private final DisplayBoards boards;
  private final VirtualTabPackets layout;
  private final BossBarView bossBars;
  private final NormalTabView normalTab;
  private final Map<UUID, Header> headers = new HashMap<>();
  private final AtomicBoolean dirty = new AtomicBoolean(true);
  private TextRenderer renderer;
  private BukkitTask task;
  private long tick;
  private int sidebarConflicts, tagConflicts, headerConflicts, sortConflicts;

  public DisplayController(
      JavaPlugin plugin,
      DisplayService service,
      PrefixController prefixes,
      PluginSettings settings) {
    this.plugin = plugin;
    this.service = service;
    this.prefixes = prefixes;
    nativeCanvasSupported = settings.minecraft.bitmapFonts();
    tags = new NameTagView(plugin, settings.minecraft);
    sidebar = new SidebarView(settings.minecraft);
    headerFooter = new HeaderFooterAccess(plugin.getLogger(), settings.minecraft);
    boards = new DisplayBoards(plugin.getServer());
    layout = new VirtualTabPackets(plugin);
    bossBars = new BossBarView(plugin.getServer());
    normalTab = new NormalTabView(plugin, settings.minecraft);
    renderer = new TextRenderer(settings);
    prefixes.managedDisplays();
    prefixes.displayDesign(() -> service.snapshot().design);
    prefixes.animations(id -> service.preferences(id).animations);
    prefixes.displayChanged(() -> dirty.set(true));
    service.onPreferencesChanged(prefixes::request);
    java.util.concurrent.atomic.AtomicReference<String> prefixDocument=new java.util.concurrent.atomic.AtomicReference<>(service.snapshot().prefixDocument);
    service.onChanged(
        () -> {
          dirty.set(true);
          String next=service.snapshot().prefixDocument;
          if(!next.equals(prefixDocument.getAndSet(next)))prefixes.requestAll();
        });
  }

  public void reload(PluginSettings settings) {
    renderer = new TextRenderer(settings);
    dirty.set(true);
  }

  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 2, 1);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    dirty.set(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    headers.remove(id);
    headerFooter.forget(id);
    layout.forget(id);
    bossBars.forget(id);
    screenHud.forget(id);
    normalTab.forget(id);
    tags.forget(id);
    boards.restore(e.getPlayer());
    boards.forget(id);
    dirty.set(true);
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    dirty.set(true);
  }

  @EventHandler
  public void mode(PlayerGameModeChangeEvent e) {
    dirty.set(true);
  }

  private List<Player> visible(Player viewer, DisplayDesign d) {
    List<Player> players = new ArrayList<>();
    for (Player p : plugin.getServer().getOnlinePlayers()) if (viewer.canSee(p)) players.add(p);
    if (service.preferences(viewer.getUniqueId()).sorting)
      players.sort(
          TabFormatting.order(
              d,
              p -> prefixes.group(p.getUniqueId()),
              p -> prefixes.weight(p.getUniqueId()),
              layout::ping));
    else
      players.sort(
          Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
              .thenComparing(Player::getUniqueId));
    return players;
  }

  private Map<String, String> values(
      Player target, Player viewer, DisplayDesign d, int online, int overflow) {
    Map<String, String> v = new LinkedHashMap<>();
    v.put("player", target.getName());
    v.put("prefix", prefixes.prefix(target, viewer));
    v.put("group", prefixes.group(target.getUniqueId()));
    v.put("server", d.serverName);
    v.put("online", String.valueOf(online));
    v.put("max", String.valueOf(plugin.getServer().getMaxPlayers()));
    v.put("world", target.getWorld().getName());
    v.put("ping", String.valueOf(layout.ping(target)));
    LocalDateTime now = LocalDateTime.now();
    v.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
    v.put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
    v.put(
        "health",
        java.math.BigDecimal.valueOf(target.getHealth()).stripTrailingZeros().toPlainString());
    v.put(
        "max_health",
        java.math.BigDecimal.valueOf(target.getMaxHealth()).stripTrailingZeros().toPlainString());
    v.put("food", String.valueOf(target.getFoodLevel()));
    v.put(
        "experience",
        String.valueOf(Math.round(target.getExp() * 1000) / 10d).replaceAll("\\.0$", ""));
    v.put("level", String.valueOf(target.getLevel()));
    v.put("x", String.valueOf(target.getLocation().getBlockX()));
    v.put("y", String.valueOf(target.getLocation().getBlockY()));
    v.put("z", String.valueOf(target.getLocation().getBlockZ()));
    v.put("layout_overflow", String.valueOf(overflow));
    PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
    DisplayDesign.GroupStyle style = d.groupStyle(prefixes.group(target.getUniqueId()));
    v.put("suffix", "");
    v.put("tab_name", "");
    v.put("display_name", target.getName());
    v.put("weight", String.valueOf(prefixes.weight(target.getUniqueId())));
    v.put(
        "suffix",
        TabFormatting.suffix(
            d,
            style,
            prefixes.suffix(target.getUniqueId()),
            v,
            renderer,
            tick,
            prefs.suffixes,
            prefs.animations,id->prefixes.screenImage(id,viewer,tick,prefs.animations,"")));
    v.put("display_name", TabFormatting.name(target.getName(), style, renderer));
    v.put("tab_name", line(d.tabPlayerFormat, v, prefs.animations));
    return v;
  }

  private String line(DisplayDesign.Line line, Map<String, String> variables, boolean animate) {
    String legacy = renderer.legacy(renderer.display(line.source(tick, animate), variables));
    return animate ? DisplayAnimation.animate(legacy,line,tick) : legacy;
  }

  private List<String> lines(
      List<DisplayDesign.Line> lines, Map<String, String> variables, boolean animate) {
    List<String> out = new ArrayList<>();
    for (DisplayDesign.Line l : lines) out.add(line(l, variables, animate));
    return out;
  }

  private void render() {
    tick++;
    DisplayDesign d = service.snapshot().design;
    boolean normalUpdate =
        dirty.getAndSet(false)
            || tick % d.refreshTicks == 0
            || tabAnimationDue(d)
            || displayAnimationDue(d)
            || prefixes.graphicalAnimationDue(tick);
    if (!normalUpdate) {
      boolean bandAnimation=headerAnimationDue(d);
      boolean animated = bandAnimation ||
          d.screenEnabled
              || d.sidebarEnabled && !d.sidebarMode.equals("NATIVE")
              || d.bossBarsEnabled
                  && (d.bossBarMode.equals("ROTATE")
                      || d.bossBars.stream()
                          .anyMatch(
                              bar ->
                                  bar.enabled
                                      && (bar.timed() || bar.binding.enabled() || !bar.schedule.mode.equals("ALWAYS") || !bar.line.animation.equals("NONE"))));
      if (!animated || tick % 2 != 0) return;
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        try {
          PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
          List<Player> visible = visible(viewer, d);
          Map<String,String> variables=values(viewer,viewer,d,visible.size(),overflow(d,visible.size()));
          if (bandAnimation || d.sidebarEnabled && !d.sidebarMode.equals("NATIVE"))
            header(
                viewer,
                d,
                prefs,
                variables);
          screen(
              viewer,
              d,
              prefs,
              variables);
          bossBars.render(
              viewer,
              d,
              prefs,
              variables,
              tick,
              service.snapshot().revision,
              (source, vars) -> line(source, vars, prefs.animations));
        } catch (RuntimeException e) {
          plugin
              .getLogger()
              .warning("Boss bar update skipped: " + me.snowsun.tabprefix.util.Failures.message(e));
        }
      }
      return;
    }
    Set<Scoreboard> sideboards =
        Collections.newSetFromMap(new IdentityHashMap<Scoreboard, Boolean>());
    Map<Scoreboard, List<Player>> tagboards = new IdentityHashMap<>();
    sidebarConflicts = 0;
    tagConflicts = 0;
    headerConflicts = 0;
    sortConflicts = 0;
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      try {
        PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
        List<Player> visible = visible(viewer, d);
        Map<String, String> variables =
            values(viewer, viewer, d, visible.size(), overflow(d, visible.size()));
        header(viewer, d, prefs, variables);
        screen(viewer, d, prefs, variables);
        bossBars.render(
            viewer,
            d,
            prefs,
            variables,
            tick,
            service.snapshot().revision,
            (source, vars) -> line(source, vars, prefs.animations));
        boolean side = d.sidebarEnabled && prefs.sidebar && d.sidebarMode.equals("NATIVE"),
            names = d.nameTagsEnabled && prefs.nameTags,
            order =
                d.nativeSortEnabled
                    && prefs.sorting
                    && (!d.layoutEnabled || !prefs.layout || !layout.available());
        Scoreboard board = boards.board(viewer, side || names || order);
        if (side) {
          if (boards.personal(board)) {
            if (sidebar.render(
                board,
                renderer.legacy(renderer.display(d.title, variables)),
                lines(d.sidebar, variables, prefs.animations))) sideboards.add(board);
            else sidebarConflicts++;
          } else sidebarConflicts++;
        }
        if (names || order) {
          if (boards.personal(board))
            tagboards.computeIfAbsent(board, key -> new ArrayList<>()).add(viewer);
          else {
            if (names) tagConflicts++;
            if (order) sortConflicts++;
          }
        }
        if((!d.layoutEnabled||!prefs.layout||!layout.available())&&layout.active(viewer)){layout.restore(viewer,visible);normalTab.invalidate(viewer.getUniqueId());}
        Map<Player, String> normal = new LinkedHashMap<>();
        for (Player target : visible) {
          Map<String, String> vars =
              values(target, viewer, d, visible.size(), overflow(d, visible.size()));
          normal.put(
              target,
              d.tabFormatEnabled
                  ? vars.get("tab_name")
                  : (prefixes.tabActive()
                      ? prefixes.prefix(target, viewer) + "§r" + target.getName()
                      : normalTab.original(target)) + vars.get("suffix"));
        }
        normalTab.render(
            viewer,
            normal,
            target -> {
              Map<String, String> vars = values(target, target, d, visible.size(), 0);
              vars.put("prefix", prefixes.textPrefix(target.getUniqueId()));
              vars.put("suffix", TabFormatting.suffix(d,
                  d.groupStyle(prefixes.group(target.getUniqueId())),
                  prefixes.suffix(target.getUniqueId()), vars, renderer, tick,
                  service.preferences(target.getUniqueId()).suffixes, false, id -> ""));
              return d.tabFormatEnabled
                  ? line(d.tabPlayerFormat, vars, false)
                  : (prefixes.tabActive()
                      ? prefixes.textPrefix(target.getUniqueId()) + "§r" + target.getName()
                      : normalTab.original(target)) + vars.get("suffix");
            });
        if (d.layoutEnabled && prefs.layout){if(!layout.render(viewer,cells(viewer,d,prefs,visible),visible,tick))normalTab.invalidate(viewer.getUniqueId());}
        else layout.restore(viewer, visible);
      } catch (RuntimeException e) {
        plugin
            .getLogger()
            .warning("Display update skipped: " + me.snowsun.tabprefix.util.Failures.message(e));
      }
    }
    sidebar.retain(sideboards);
    tags.retain(tagboards.keySet());
    for (Map.Entry<Scoreboard, List<Player>> entry : tagboards.entrySet()) {
      Player sample = entry.getValue().get(0);
      PlayerDisplaySettings prefs = service.preferences(sample.getUniqueId());
      boolean names = d.nameTagsEnabled && prefs.nameTags,
          order =
              d.nativeSortEnabled
                  && prefs.sorting
                  && (!d.layoutEnabled || !prefs.layout || !layout.available());
      List<Player> sorted = visible(sample, d);
      if (!order)
        sorted.sort(
            Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Player::getUniqueId));
      Map<UUID, Integer> positions = new HashMap<>();
      for (int i = 0; i < sorted.size(); i++) positions.put(sorted.get(i).getUniqueId(), i);
      for (Player target : plugin.getServer().getOnlinePlayers()) {
        boolean see = true;
        String prefix = null;
        for (Player viewer : entry.getValue()) {
          see &= viewer.canSee(target);
          String rendered = prefixes.prefix(target, viewer);
          if (prefix == null) prefix = rendered;
          else if (!prefix.equals(rendered)) prefix = prefixes.textPrefix(target.getUniqueId());
        }
        if (!see) {
          tags.remove(entry.getKey(), target.getUniqueId());
          continue;
        }
        if (entry.getKey().getEntryTeam(target.getName()) != null
            && !entry.getKey().getEntryTeam(target.getName()).getName().startsWith("tp")) {
          if (names) tagConflicts++;
          if (order) sortConflicts++;
          continue;
        }
        Map<String, String> vars = values(target, sample, d, sorted.size(), 0);
        vars.put("prefix", prefix == null ? "" : prefix);
        boolean applied =
            tags.apply(
                entry.getKey(),
                target,
                names ? renderer.legacy(renderer.display(d.nameTagPrefix, vars)) : "",
                names ? renderer.legacy(renderer.display(d.nameTagSuffix, vars)) : "",
                names ? d.visibility : "ALWAYS",
                names ? d.collision : "ALWAYS",
                positions.getOrDefault(target.getUniqueId(), 99999));
        if (!applied) {
          if (names) tagConflicts++;
          if (order) sortConflicts++;
        }
      }
    }
  }

  private boolean headerAnimationDue(DisplayDesign d) {
    if(tick%2!=0)return false;
    boolean animated=d.headerEnabled&&d.header.stream().anyMatch(l->!l.animation.equals("NONE")) || d.footerEnabled&&d.footer.stream().anyMatch(l->!l.animation.equals("NONE"));
    animated|=d.tabLayoutMode.equals("COMPACT")&&(d.headerEnabled||d.footerEnabled);
    if(!animated)return false;
    for(Player viewer:plugin.getServer().getOnlinePlayers()){
      PlayerDisplaySettings prefs=service.preferences(viewer.getUniqueId());
      if(prefs.headerFooter&&prefs.animations)return true;
    }
    return false;
  }

  private boolean displayAnimationDue(DisplayDesign d) {
    if (tick % 2 != 0) return false;
    boolean animated = d.sidebarEnabled && d.sidebar.stream().anyMatch(l -> !l.animation.equals("NONE"))
        || d.layoutEnabled && d.slots.stream().anyMatch(s -> !s.line.animation.equals("NONE"));
    if (!animated) return false;
    for (Player viewer : plugin.getServer().getOnlinePlayers())
      if (service.preferences(viewer.getUniqueId()).animations) return true;
    return false;
  }

  private boolean tabAnimationDue(DisplayDesign d) {
    if (tick % 2 != 0) return false;
    boolean images =
        d.layoutEnabled
            && d.slots.stream()
                .anyMatch(slot -> slot.kind.equals("IMAGE") && prefixes.imageAnimated(slot.image));
    boolean customPrefix = d.groupStyles.stream().anyMatch(s->s.prefixMode.equals("CUSTOM") && !s.prefix.animation.equals("NONE") || s.prefixParts.stream().anyMatch(p->p.enabled&&(!p.line.animation.equals("NONE")||prefixes.imageAnimated(p.image))));
    boolean format = customPrefix || d.tabFormatEnabled && !d.tabPlayerFormat.animation.equals("NONE");
    boolean suffix =
        d.suffixEnabled
            && d.groupStyles.stream()
                .anyMatch(
                    style ->
                        style.suffixMode.equals("CUSTOM")
                            && !style.suffix.animation.equals("NONE") || prefixes.imageAnimated(style.suffixImage) || style.suffixParts.stream().anyMatch(p->p.enabled&&(!p.line.animation.equals("NONE")||prefixes.imageAnimated(p.image))));
    format|=d.layoutEnabled&&d.tabLayoutMode.equals("COMPACT");
    if (!format && !suffix && !images) return false;
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
      if (prefs.animations && (format || suffix && prefs.suffixes || images && prefs.layout))
        return true;
    }
    return false;
  }

  private static int overflow(DisplayDesign d, int online) {
    Set<Integer> indexes = new HashSet<>();
    for (DisplayDesign.Slot slot : d.slots)
      if (slot.kind.equals("PLAYER") && slot.playerIndex <= online) indexes.add(slot.playerIndex);
    return Math.max(0, online - indexes.size());
  }

  private static final class TabFrame {
    final List<VirtualTabPackets.Cell> cells;final int[] source;final int columns,rows,contentWidth;
    TabFrame(List<VirtualTabPackets.Cell> cells,int[] source,int columns,int rows,int width){this.cells=cells;this.source=source;this.columns=columns;this.rows=rows;contentWidth=width;}
  }
  private List<VirtualTabPackets.Cell> cells(Player viewer,DisplayDesign d,PlayerDisplaySettings prefs,List<Player> visible){return tabFrame(viewer,d,prefs,visible,true,true).cells;}
  private TabFrame tabFrame(Player viewer,DisplayDesign d,PlayerDisplaySettings prefs,List<Player> visible,boolean decorate,boolean arrange){
    List<VirtualTabPackets.Cell> cells=new ArrayList<>();int overflow=overflow(d,visible.size());int[] above=new int[80],below=new int[80],widths=new int[80];NativeHudLayout hud=prefixes.canvas(viewer);
    for(DisplayDesign.Slot slot:d.slots){int i=cells.size();Player target=slot.kind.equals("PLAYER")&&slot.playerIndex<=visible.size()?visible.get(slot.playerIndex-1):null;
      Map<String,String> vars=values(target==null?viewer:target,viewer,d,visible.size(),overflow);String text=slot.kind.equals("EMPTY")||slot.kind.equals("PLAYER")&&target==null?"":line(slot.line,vars,prefs.animations);
      if(slot.kind.equals("IMAGE"))text=prefixes.screenImage(slot.image,viewer,tick,prefs.animations,text);
      final int sourceIndex=i;if(nativeCanvasSupported&&text.isEmpty()&&(slot.kind.equals("EMPTY")||slot.kind.equals("TEXT"))&&d.artwork.box("CELL",i)!=null&&d.artwork.shapes.stream().anyMatch(shape->shape.enabled&&shape.surface.equals("CELL")&&shape.index==sourceIndex))text=" ";
      if(!text.isEmpty()||target!=null){List<AssetDescriptor> images=new ArrayList<>();if(target!=null)images.addAll(prefixes.decorations(target,prefs.suffixes));if(slot.image!=null&&prefixes.image(slot.image)!=null)images.add(prefixes.image(slot.image));for(AssetDescriptor a:images)if(prefixes.imageAvailable(viewer,a.id)&&containsGlyph(text,a)){above[i]=Math.max(above[i],TabMetrics.above(a));below[i]=Math.max(below[i],TabMetrics.below(a));}
        Map<Integer,Integer> stable=new HashMap<>(hud.foreignWidths);for(AssetDescriptor a:images){int max=0;for(int code:a.glyphs())max=Math.max(max,hud.foreignWidths.getOrDefault(code,(int)Math.round(a.width*a.renderHeight/(double)a.height)+1));for(int code:a.glyphs())stable.put(code,max);}widths[i]=TabMetrics.width(text,stable);DisplayArtwork.Box box=d.artwork.box("CELL",i);if(box!=null)widths[i]=Math.max(widths[i],box.width);
      }cells.add(new VirtualTabPackets.Cell(text,target));
    }
    int[] sources=new int[80];for(int n=0;n<80;n++)sources[n]=n;
    if(!arrange)return new TabFrame(cells,sources,4,20,TabMetrics.contentWidth(d.tabWidth,4,d.tabColumnGap));
    NativeHudLayout.Profile font=hud.get("tab:compact",0,7,"");
    for(int n=0;n<cells.size();n++){VirtualTabPackets.Cell cell=cells.get(n);String text=compactImages(cell.text,hud);cells.set(n,new VirtualTabPackets.Cell(font==null?text:TabMetrics.compactText(font,text,hud.metrics).text,cell.player));above[n]=below[n]=0;}
    int columns=4,rows=20,limit=TabMetrics.contentWidth(d.tabWidth,4,d.tabColumnGap);String dots=font==null?"..":NativeHudLayout.encode(font,"..").text;
    if(d.tabLayoutMode.equals("COMPACT")){int[] insets=new int[80];for(int n=0;n<80;n++){DisplayArtwork.Box box=d.artwork.box("CELL",n);if(box!=null)insets[n]=Math.min(8,box.padding*2);}CompactTabLayout.Result r=CompactTabLayout.arrange(cells,insets,d.tabWidth,d.tabColumnGap,hud.metrics,font!=null,tick,prefs.animations,dots);cells=r.cells;sources=r.source;columns=r.columns;rows=r.rows;limit=r.contentWidth;}
    else{if(d.tabAdaptive){AdaptiveTabLayout.Result r=AdaptiveTabLayout.arrange(cells,above,below,widths,d.tabColumnGap,hud.metrics,decorate&&hud.profiles.containsKey("action"));cells=r.cells;sources=r.source;}for(int n=0;n<cells.size();n++){VirtualTabPackets.Cell cell=cells.get(n);cells.set(n,new VirtualTabPackets.Cell(TabMetrics.fit(cell.text,hud.metrics,limit,tick,prefs.animations,dots),cell.player));}}
    if(decorate){boolean heads=me.snowsun.tabprefix.infrastructure.bukkit.TabGeometry.heads(plugin.getServer(),d),fullRows=false;
      for(int source:sources){DisplayArtwork.Box box=source<0?null:d.artwork.box("CELL",source);fullRows|=box!=null&&box.fit.equals("ROW")&&hud.get(box.key()+":row",0,8,NativeHudLayout.compactSignature(d,box))!=null;}
      for(int n=0;n<cells.size();n++){VirtualTabPackets.Cell cell=cells.get(n);DisplayArtwork.Box box=sources[n]<0?null:d.artwork.box("CELL",sources[n]);String text=box==null?cell.text:ArtworkText.cell(hud,d,sources[n],cell.text,limit,columns,heads,tick,prefs.animations);
        if(fullRows&&font!=null)text+="§r"+NativeHudLayout.shift(limit-TabMetrics.width(text,hud.metrics));cells.set(n,new VirtualTabPackets.Cell(text,cell.player));}
    }
    if(!decorate&&font!=null)for(int n=0;n<cells.size();n++){VirtualTabPackets.Cell cell=cells.get(n);cells.set(n,new VirtualTabPackets.Cell(readableTab(cell.text,hud),cell.player));}
    return new TabFrame(cells,sources,columns,rows,limit);
  }
  private final Map<NativeHudLayout,Map<Integer,String>> iconCache=new IdentityHashMap<>();private Collection<AssetDescriptor> iconAssets;
  private String compactImages(String text,NativeHudLayout hud){if(!nativeCanvasSupported)return text;Collection<AssetDescriptor> assets=prefixes.decorationsSnapshot();if(iconAssets!=assets){iconAssets=assets;iconCache.clear();}Map<Integer,String> replacements=iconCache.get(hud);
    if(replacements==null){replacements=new HashMap<>();for(AssetDescriptor a:assets){NativeHudLayout.Profile icon=hud.get("tab:image:"+a.id,0,8,a.id.toString());if(a.assigned())for(int n=0;n<a.frames();n++)replacements.put(a.glyphCode(n),icon!=null&&n<icon.frames()?icon.glyph(n):"◇");}if(iconCache.size()>=8)iconCache.clear();iconCache.put(hud,replacements);}
    StringBuilder out=new StringBuilder();for(int n=0;n<text.length();){int c=text.codePointAt(n);n+=Character.charCount(c);String r=replacements.get(c);if(r==null)out.appendCodePoint(c);else out.append(r);}return out.toString();
  }
  private String readableTab(String text,NativeHudLayout hud){return hud.readable(text,prefixes::image);}

  private static boolean containsGlyph(String text,AssetDescriptor image){for(int code:image.glyphs())if(text.indexOf((char)code)>=0)return true;return false;}

  private String tabLines(Player viewer,DisplayDesign d,String surface,List<DisplayDesign.Line> source,Map<String,String> vars,boolean animate){NativeHudLayout hud=prefixes.canvas(viewer);List<String> out=new ArrayList<>();for(int n=0;n<source.size();n++){String text=line(source.get(n),vars,animate);if(d.tabLayoutMode.equals("COMPACT")){NativeHudLayout.Profile font=hud.get("tab:compact",0,7,"");text=compactImages(text,hud);if(font!=null&&d.artwork.box(surface,n)==null)text=TabMetrics.compactText(font,text,hud.metrics).text;out.add(ArtworkText.compact(hud,d,surface,n,text,d.tabWidth,tick,animate));}else out.add(ArtworkText.tab(hud,d,surface,n,text));}return d.tabLayoutMode.equals("COMPACT")?TabBandLayout.fit(out,d.tabBandRows,tick,animate):String.join("\n",out);}

  private void header(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> variables) {
    boolean fallback = sidebarFallback(viewer, d, prefs);
    boolean enabled = prefs.headerFooter && (d.headerEnabled || d.footerEnabled) || fallback;
    if (!enabled) {
      restoreHeader(viewer);
      return;
    }
    Header h = headers.get(viewer.getUniqueId());
    if (h == null) {
      h = new Header(viewer, headerFooter);
      headers.put(viewer.getUniqueId(), h);
    }
    if (d.headerEnabled && prefs.headerFooter) {
      if (h.ownsHeader && !Objects.equals(headerFooter.header(viewer), h.header)) headerConflicts++;
      else {
        String top = tabLines(viewer,d,"HEADER",d.header,variables,prefs.animations);
        boolean sent=h.ownsHeader&&Objects.equals(top,h.sentHeader)||headerFooter.writeHeader(viewer,top);
        if(sent){h.sentHeader=top;h.header=headerFooter.header(viewer);h.ownsHeader=true;}else h.ownsHeader=false;
      }
    } else if (h.ownsHeader) {
      if (Objects.equals(headerFooter.header(viewer), h.header))
        headerFooter.header(viewer, h.beforeHeader);
      h.ownsHeader = false;
    }
    if (d.footerEnabled && prefs.headerFooter || fallback) {
      if (h.ownsFooter && !Objects.equals(headerFooter.footer(viewer), h.footer)) headerConflicts++;
      else {
        String bottom =
            d.footerEnabled && prefs.headerFooter
                ? tabLines(viewer,d,"FOOTER",d.footer,variables,prefs.animations)
                : "";
        if (fallback) {
          String panel =
              renderer.legacy(renderer.display(d.title, variables))
                  + "\n"
                  + String.join("\n", lines(d.sidebar, variables, prefs.animations));
          if(d.tabLayoutMode.equals("COMPACT")){List<String> parts=new ArrayList<>();if(!bottom.isEmpty())parts.addAll(Arrays.asList(bottom.split("\n",-1)));for(String row:panel.split("\n",-1))parts.add(TabMetrics.fit(row,prefixes.canvas(viewer).metrics,d.tabWidth,tick,prefs.animations));bottom=TabBandLayout.fit(parts,d.tabBandRows,tick,prefs.animations);}else bottom += (bottom.isEmpty() ? "" : "\n\n") + panel;
        }
        if ((!h.ownsFooter || !Objects.equals(bottom,h.sentFooter)) && !headerFooter.writeFooter(viewer,bottom)){h.ownsFooter=false;return;}
        h.sentFooter=bottom;
        h.footer = headerFooter.footer(viewer);
        h.ownsFooter = true;
      }
    } else if (h.ownsFooter) {
      if (Objects.equals(headerFooter.footer(viewer), h.footer))
        headerFooter.footer(viewer, h.beforeFooter);
      h.ownsFooter = false;
    }
  }

  private JsonArray animationSources(List<DisplayDesign.Line> source,Map<String,String> vars) {
    JsonArray rows=new JsonArray();
    for(DisplayDesign.Line line:source) {
      JsonArray frames=new JsonArray();
      if(line.animation.equals("FRAMES"))for(String frame:line.frames)frames.add(renderer.legacy(renderer.display(frame,vars)));
      else frames.add(renderer.legacy(renderer.display(line.text,vars)));
      rows.add(frames);
    }
    return rows;
  }

  private void restoreHeader(Player p) {
    Header h = headers.remove(p.getUniqueId());
    if (h == null) return;
    if (h.ownsHeader && Objects.equals(headerFooter.header(p), h.header))
      headerFooter.header(p, h.beforeHeader);
    if (h.ownsFooter && Objects.equals(headerFooter.footer(p), h.footer))
      headerFooter.footer(p, h.beforeFooter);
  }

  /**
   * An authenticated read-only preview uses the same renderer and visibility rules as the server.
   */
  public JsonObject preview(Player viewer, DisplayDesign d, long previewTick) {
    long before = tick;
    tick = previewTick;
    try {
      List<Player> visible = visible(viewer, d);
      PlayerDisplaySettings prefs = service.preferences(viewer.getUniqueId());
      Map<String, String> vars =
          values(viewer, viewer, d, visible.size(), overflow(d, visible.size()));
      JsonObject j = new JsonObject();
      j.add("header", jsonLines(lines(d.header, vars, prefs.animations)));
      j.add("footer", jsonLines(lines(d.footer, vars, prefs.animations)));
      JsonObject animations=new JsonObject();animations.add("header",animationSources(d.header,vars));animations.add("footer",animationSources(d.footer,vars));
      j.add("animationSources",animations);j.addProperty("animationsEnabled",prefs.animations);j.addProperty("headerFooterEnabled",prefs.headerFooter);
      Header ownership=headers.get(viewer.getUniqueId());j.addProperty("footerConflict",ownership!=null&&ownership.ownsFooter&&!Objects.equals(headerFooter.footer(viewer),ownership.footer));
      j.add("sidebar", jsonLines(lines(d.sidebar, vars, prefs.animations)));
      JsonObject hud = new JsonObject();
      for (Map.Entry<String, String> e : screenText(viewer, d, prefs, vars).entrySet())
        hud.add(e.getKey(), rich(e.getValue()));
      j.add("screen", hud);
      j.addProperty(
          "scoreNumbers",
          d.sidebarMode.equals("NATIVE") ? sidebar.numberStatus() : "scoreless-panel");
      j.addProperty(
          "sidebarPlacement",
          d.sidebarMode.equals("NATIVE")
              ? "SIDEBAR"
              : sidebarFallback(viewer, d, prefs) ? "TAB_FOOTER" : "FONT_CANVAS");
      j.addProperty("canvasLoaded", prefixes.canvas(viewer).present());
      JsonArray elements = new JsonArray();
      for (DisplayDesign.Screen item : d.screen) {
        JsonObject row = new JsonObject();
        row.addProperty("id", item.id);
        row.addProperty("anchor", item.anchor);
        row.addProperty("x", item.x);
        row.addProperty("y", item.y);
        row.addProperty("height", item.height);
        row.addProperty("reason", screenReason(viewer, d, prefs, item));
        row.add("text", rich(line(item.line, vars, prefs.animations)));
        elements.add(row);
      }
      j.add("screenElements", elements);
      JsonArray bars = new JsonArray();
      for (BossBarView.Frame frame :
          bossBars.preview(
              viewer,
              d,
              prefs,
              vars,
              previewTick,
              (source, values) -> line(source, values, prefs.animations))) {
        JsonObject bar = new JsonObject();
        bar.addProperty("id", frame.design.id);
        bar.add("title", rich(frame.title));
        bar.addProperty("color", frame.design.color);
        bar.addProperty("style", frame.design.style);
        bar.addProperty("progress", frame.progress);
        bar.addProperty("active", frame.active);
        bar.addProperty("reason", frame.reason);
        bars.add(bar);
      }
      j.add("bossBars", bars);
      j.add("title", rich(renderer.legacy(renderer.display(d.title, vars))));
      List<String> slots = new ArrayList<>();
      for (VirtualTabPackets.Cell cell : tabFrame(viewer,d,prefs,visible,false,false).cells) slots.add(cell.text);
      j.add("slots", jsonLines(slots));TabFrame table=tabFrame(viewer,d,prefs,visible,false,true);JsonObject layoutPreview=new JsonObject();List<String> rendered=new ArrayList<>();for(VirtualTabPackets.Cell cell:table.cells)rendered.add(cell.text);layoutPreview.add("cells",jsonLines(rendered));layoutPreview.addProperty("columns",table.columns);layoutPreview.addProperty("rows",table.rows);layoutPreview.addProperty("width",d.tabWidth);layoutPreview.addProperty("mode",d.tabLayoutMode);layoutPreview.addProperty("heads",me.snowsun.tabprefix.infrastructure.bukkit.TabGeometry.heads(plugin.getServer(),d));JsonArray origins=new JsonArray();for(int source:table.source)origins.add(source);layoutPreview.add("source",origins);JsonArray playerCells=new JsonArray();for(VirtualTabPackets.Cell cell:table.cells)playerCells.add(cell.player!=null);layoutPreview.add("playerCells",playerCells);j.add("tabLayout",layoutPreview);
      List<String> players = new ArrayList<>();
      for (Player target : visible) {
        Map<String, String> variables =
            values(target, viewer, d, visible.size(), overflow(d, visible.size()));
        players.add(
            d.tabFormatEnabled
                ? variables.get("tab_name")
                : (prefixes.tabActive()
                    ? prefixes.prefix(target, viewer) + "§r" + target.getName()
                    : normalTab.original(target)) + variables.get("suffix"));
      }
      j.add("players", jsonLines(players));
      j.add("playerFormat", rich(vars.get("tab_name")));
      JsonArray styles = new JsonArray();
      for (DisplayDesign.GroupStyle style : d.groupStyles) {
        Player example =
            visible.stream()
                .filter(p -> prefixes.group(p.getUniqueId()).equalsIgnoreCase(style.group))
                .findFirst()
                .orElse(viewer);
        Map<String, String> values = values(example, viewer, d, visible.size(), 0);
        values.put("group", style.group);
        String suffix =
            TabFormatting.suffix(
                d,
                style,
                prefixes.group(example.getUniqueId()).equalsIgnoreCase(style.group)
                    ? prefixes.suffix(example.getUniqueId())
                    : "",
                values,
                renderer,
                tick,
                prefs.suffixes,
                prefs.animations,
                id -> prefixes.screenImage(id, viewer, tick, prefs.animations, ""));
        JsonObject row = new JsonObject();
        row.addProperty("group", style.group);
        row.add("suffix", rich(suffix));
        row.add("name", rich(TabFormatting.name(example.getName(), style, renderer)));
        styles.add(row);
      }
      j.add("groupStyles", styles);
      j.add(
          "nameTag",
          rich(
              renderer.legacy(renderer.display(d.nameTagPrefix, vars))
                  + "§r"
                  + viewer.getName()
                  + renderer.legacy(renderer.display(d.nameTagSuffix, vars))));
      j.addProperty("online", visible.size());
      j.addProperty("overflow", overflow(d, visible.size()));
      j.addProperty("layoutAvailable", layout.available());
      j.addProperty("sidebarConflicts", sidebarConflicts);
      j.addProperty("nameTagConflicts", tagConflicts);
      j.addProperty("headerConflicts", headerConflicts);
      j.addProperty("sortConflicts", sortConflicts);
      return j;
    } finally {
      tick = before;
    }
  }

  private static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();

  private static JsonElement rich(String text) {
    return new JsonParser()
        .parse(GsonComponentSerializer.gson().serialize(LEGACY.deserialize(text)));
  }

  private static JsonArray jsonLines(List<String> lines) {
    JsonArray a = new JsonArray();
    for (String s : lines) a.add(rich(s));
    return a;
  }

  public String diagnostic() {
    return "displayRevision="
        + service.snapshot().revision
        + ", fixedTab="
        + layout.available()
        + ", sidebarMode="
        + service.snapshot().design.sidebarMode
        + ", nativeCanvasSupported="
        + nativeCanvasSupported
        + ", scoreNumbers="
        + (service.snapshot().design.sidebarMode.equals("NATIVE")
            ? sidebar.numberStatus()
            : "scoreless-panel")
        + ", sidebarConflicts="
        + sidebarConflicts
        + ", nametagConflicts="
        + tagConflicts
        + ", headerConflicts="
        + headerConflicts
        + ", bossBars="
        + bossBars.count()
        + ", sortConflicts="
        + sortConflicts
        + ", "
        + layout.diagnostic()
        + ", "
        + normalTab.diagnostic()
        + ", "
        + headerFooter.diagnostic();
  }

  public void close() {
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    sidebar.close();
    tags.close();
    bossBars.close();
    screenHud.close();
    normalTab.close();
    boards.close();
    for (Player p : plugin.getServer().getOnlinePlayers()) {
      restoreHeader(p);
      layout.restore(p, visible(p, service.snapshot().design));
    }
    headers.clear();
    headerFooter.clear();
  }

  private String screenReason(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, DisplayDesign.Screen item) {
    if (!d.screenEnabled) return "SERVER_DISABLED";
    if (!prefs.screen) return "PERSONAL_DISABLED";
    if (!item.enabled) return "DISABLED";
    if (!item.permission.isEmpty() && !viewer.hasPermission(item.permission)) return "PERMISSION";
    if (!item.worlds.isEmpty() && !item.worlds.contains(viewer.getWorld().getName()))
      return "WORLD";
    return "";
  }

  private boolean sidebarFallback(Player viewer, DisplayDesign d, PlayerDisplaySettings prefs) {
    if (!d.sidebarEnabled || !prefs.sidebar || d.sidebarMode.equals("NATIVE")) return false;
    if (d.sidebarMode.equals("TAB")) return true;
    NativeHudLayout h = prefixes.canvas(viewer);
    if(h.profiles.get("sidebar:title")==null)return true;
    return false;
  }

  private NativeHudLayout.Profile screenProfile(NativeHudLayout layout, DisplayDesign.Screen item) {
    String image = item.image == null ? "" : item.image.toString();
    NativeHudLayout.Profile exact = layout.get("screen:" + item.id, item.y, item.height, image);
    if (exact != null) return exact;
    NativeHudLayout.Profile previous = layout.profiles.get("screen:" + item.id);
    return previous != null && previous.image.equals(image) ? previous : null;
  }

  private Map<String, String> screenText(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> vars) {
    Map<String, String> text = new LinkedHashMap<>();
    for (DisplayDesign.Screen item : d.screen) {
      if (!screenReason(viewer, d, prefs, item).isEmpty()) continue;
      if (item.anchor.equals("FREE_XY")
          && screenProfile(prefixes.canvas(viewer), item) != null) continue;
      String value = line(item.line, vars, prefs.animations);
      if (item.kind.equals("IMAGE"))
        value = prefixes.screenImage(item.image, viewer, tick, prefs.animations, value);
      if (!value.isEmpty())
        text.merge(
            item.anchor.equals("FREE_XY") ? "ACTION_BAR" : item.anchor,
            value,
            (a, b) -> a + " §r" + b);
    }
    for (String anchor : Arrays.asList("TITLE", "SUBTITLE", "ACTION_BAR"))
      text.putIfAbsent(anchor, "");
    return text;
  }

  private void screen(
      Player viewer, DisplayDesign d, PlayerDisplaySettings prefs, Map<String, String> vars) {
    Map<String, String> text = screenText(viewer, d, prefs, vars);
    NativeHudLayout h = prefixes.canvas(viewer);
    StringBuilder canvas = new StringBuilder();
    for (DisplayDesign.Screen item : d.screen)
      if (item.anchor.equals("FREE_XY") && screenReason(viewer, d, prefs, item).isEmpty()) {
        NativeHudLayout.Profile p = screenProfile(h, item);
        if (p == null) continue;
        NativeHudLayout.Encoded e;
        if (item.kind.equals("IMAGE")) {
          int frame = prefixes.imageFrame(item.image, tick, prefs.animations);
          if (frame >= p.frames()) continue;
          e = new NativeHudLayout.Encoded("§f" + p.glyph(frame), p.advance(frame));
        } else e = NativeHudLayout.encode(p, line(item.line, vars, prefs.animations));
        canvas.append(NativeHudLayout.position(e, item.x));
      }
    if (d.sidebarEnabled
        && prefs.sidebar
        && d.sidebarMode.equals("HUD")
        && !sidebarFallback(viewer, d, prefs)) {
      if(d.artwork.sidebarGraphic(d)) {
        NativeHudLayout.Profile background=h.get("sidebar:background",Math.max(-512,d.sidebarY-5),d.sidebarFontSize,d.artwork.signature(d,null));
        if(background!=null)canvas.append(NativeHudLayout.position(new NativeHudLayout.Encoded("§f"+background.glyph(0),background.advance(0)),d.sidebarX-7));
      }
      for(int n=-1;n<d.sidebar.size();n++) {
        DisplayArtwork.Placement row=d.artwork.row(n);
        NativeHudLayout.Profile p=h.get(n<0?"sidebar:title":"sidebar:"+n,d.artwork.rowY(d,n),row.size(d),"");if(p==null)p=h.profiles.get(n<0?"sidebar:title":"sidebar:"+n);if(p==null)continue;
        String value=n<0?renderer.legacy(renderer.display(d.title,vars)):line(d.sidebar.get(n),vars,prefs.animations);
        NativeHudLayout.Encoded e=DisplayAnimation.fit(p,value,h.foreignWidths,row.width(d),d.sidebarOverflow,tick,prefs.animations);
        canvas.append(NativeHudLayout.position(e,d.sidebarX-7+row.left(e.width,row.width(d))));
      }
    }

    String action = text.get("ACTION_BAR");
    if (canvas.length() > 0) {
      NativeHudLayout.Profile p = h.get("action", 0, 8, "");
      if (p != null && !action.isEmpty()) {
        NativeHudLayout.Encoded e = NativeHudLayout.encode(p, action);
        canvas.append(NativeHudLayout.position(e, -e.width / 2));
      }
      action = canvas.toString();
    }
    screenHud.render(viewer, text.get("TITLE"), text.get("SUBTITLE"), action, tick);
  }
}
