package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.ViewerTabPackets;
import me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;

/** Permission reads happen on changes; frame ticks use immutable cached state only. */
public final class PrefixController implements AutoCloseable {
  private static final class State {
    String text;
    DisplayDesign.Line source;
    Map<String,String> variables;
    final AssetDescriptor asset;
    final String group;
    final String suffix;
    final int weight;
    String graphic;
    DisplayDesign.GroupStyle style;

    State(String text, AssetDescriptor asset, String group) {
      this(text, asset, group, "", 0);
    }

    State(String text, AssetDescriptor asset, String group, String suffix, int weight) {
      this.text = text;
      this.asset = asset;
      this.group = group;
      this.suffix = suffix;
      this.weight = weight;
      graphic = text;
    }
  }

  private final JavaPlugin plugin;
  private final PrefixService texts;
  private final GraphicService graphics;
  private final LuckPermsBridge luck;
  private final PackDelivery delivery;
  private final TabListView tab;
  private final ViewerTabPackets packets;
  private final NameTagView nameTags;
  private final Set<UUID> online = ConcurrentHashMap.newKeySet();
  private final Set<UUID> pending = ConcurrentHashMap.newKeySet(),
      viewers = ConcurrentHashMap.newKeySet();
  private final Map<UUID, State> states = new HashMap<>();
  private final Map<UUID, ChatPrefix> chat = new ConcurrentHashMap<>();
  private final Map<UUID, Long> retry = new HashMap<>();
  private final Map<UUID, Integer> attempts = new HashMap<>();
  private final AtomicBoolean all = new AtomicBoolean();
  private volatile PluginSettings settings;
  private TextRenderer renderer;
  private BukkitTask task;
  private long tick;
  private volatile boolean open;
  private boolean managedDisplays;
  private java.util.function.Predicate<UUID> animatedViewer = id -> true;
  private Runnable displayChanged = () -> {};
  private java.util.function.Supplier<DisplayDesign> design = () -> null;

  public void displayDesign(java.util.function.Supplier<DisplayDesign> design) { this.design = design; requestAll(); }

  public PrefixController(
      JavaPlugin plugin,
      PrefixService texts,
      GraphicService graphics,
      LuckPermsBridge luck,
      PackDelivery delivery,
      PluginSettings settings,
      TabListView tab) {
    this.plugin = plugin;
    this.texts = texts;
    this.graphics = graphics;
    this.luck = luck;
    this.delivery = delivery;
    this.tab = tab;
    packets = new ViewerTabPackets(plugin);
    nameTags = new NameTagView(plugin, settings.minecraft);
    reload(settings);
  }

  public void start() {
    open = true;
    for (Player p : plugin.getServer().getOnlinePlayers()) watch(p);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1, 1);
  }

  public void managedDisplays() {
    managedDisplays = true;
    nameTags.close();
    for (Player player : plugin.getServer().getOnlinePlayers()) tab.restore(player);
  }

  public void displayChanged(Runnable listener) {
    displayChanged = listener;
  }

  public int weight(UUID target) {
    State s = states.get(target);
    return s == null ? 0 : s.weight;
  }

  public String suffix(UUID target) {
    State s = states.get(target);
    return s == null ? "" : s.suffix;
  }

  public boolean tabActive() {
    return settings.prefixEnabled && settings.tabEnabled;
  }

  public void animations(java.util.function.Predicate<UUID> allowed) {
    animatedViewer = allowed;
  }

  public String group(UUID target) {
    State s = states.get(target);
    return s == null ? "default" : s.group;
  }

  public String prefix(Player target,Player viewer){
    if(!settings.prefixEnabled)return "";ChatPrefix value=chat.get(target.getUniqueId());if(value!=null)return forViewer(value,viewer.getUniqueId());State state=states.get(target.getUniqueId());return basePrefix(target,viewer)+(state==null||state.style==null?"":TabFormatting.parts(state.style.prefixParts,state.variables,renderer,tick,animatedViewer.test(viewer.getUniqueId()),id->screenImage(id,viewer,tick,animatedViewer.test(viewer.getUniqueId()),"")));
  }
  public java.util.List<AssetDescriptor> decorations(Player target,boolean suffixes){
    State s=states.get(target.getUniqueId());if(s==null)return Collections.emptyList();List<AssetDescriptor> out=new ArrayList<>();if(s.asset!=null)out.add(s.asset);
    if(s.style!=null){if(suffixes&&s.style.suffixImage!=null){AssetDescriptor a=graphics.snapshot().assets.get(s.style.suffixImage);if(a!=null)out.add(a);}for(DisplayDesign.AffixPart p:s.style.prefixParts)if(p.enabled&&p.image!=null){AssetDescriptor a=graphics.snapshot().assets.get(p.image);if(a!=null)out.add(a);}if(suffixes)for(DisplayDesign.AffixPart p:s.style.suffixParts)if(p.enabled&&p.image!=null){AssetDescriptor a=graphics.snapshot().assets.get(p.image);if(a!=null)out.add(a);}}
    return out;
  }
  private String basePrefix(Player target, Player viewer) {
    State s = states.get(target.getUniqueId());
    if (s == null || !settings.prefixEnabled) return "";
    if (s.source != null && !animatedViewer.test(viewer.getUniqueId())) {
      String text = renderPrefix(s.source,s.variables,false);
      if (s.asset == null || !delivery.has(viewer.getUniqueId(),s.asset.id))
        return s.asset != null && !settings.features.fallback ? "" : text;
      return combine(new State(text,s.asset,s.group),0);
    }
    return s.asset != null && delivery.has(viewer.getUniqueId(), s.asset.id)
        ? (animatedViewer.test(viewer.getUniqueId()) ? s.graphic : combine(s, 0))
        : s.asset != null && !settings.features.fallback ? "" : s.text;
  }

  public String textPrefix(UUID target){ChatPrefix p=chat.get(target);if(p==null){State state=states.get(target);return state==null?"":state.text;}StringBuilder out=new StringBuilder(p.text);for(ChatPrefix.Segment part:p.parts)out.append(part.text);return out.toString();}

  public NativeHudLayout canvas(Player viewer) {
    return delivery == null ? NativeHudLayout.EMPTY : delivery.canvas(viewer.getUniqueId());
  }

  public int imageFrame(UUID id, long tick, boolean animate) {
    AssetDescriptor a = graphics.snapshot().assets.get(id);
    return a == null ? 0 : a.frameAt(animate && settings.features.animation ? tick : 0);
  }

  public boolean imageAvailable(Player viewer,UUID image){return image!=null&&delivery.has(viewer.getUniqueId(),image);}
  public AssetDescriptor image(UUID id){return id==null?null:graphics.snapshot().assets.get(id);}
  public Collection<AssetDescriptor> decorationsSnapshot(){return graphics.snapshot().assets.values();}

  public String screenImage(UUID id, Player viewer, long tick, boolean animate, String fallback) {
    AssetDescriptor a = graphics.snapshot().assets.get(id);
    return a != null && delivery.has(viewer.getUniqueId(), id)
        ? "§f" + a.glyph(animate && settings.features.animation ? tick : 0) + "§r"
        : fallback;
  }

  public boolean imageAnimated(UUID id) {
    AssetDescriptor a = graphics.snapshot().assets.get(id);
    return settings.features.animation && a != null && a.frames() > 1;
  }

  public boolean graphicalAnimationDue(long displayTick) {
    if (!settings.features.animation || displayTick % settings.features.period != 0) return false;
    for (State state : states.values())
      if (state.asset != null && state.asset.frames() > 1) return true;
    return false;
  }

  public void reload(PluginSettings settings) {
    this.settings = settings;
    renderer = new TextRenderer(settings);
    if (!settings.features.nameTags) nameTags.close();
    requestAll();
  }

  public void watch(Player p) {
    online.add(p.getUniqueId());
    request(p.getUniqueId());
    requestAll();
  }

  public void forget(UUID id) {
    online.remove(id);
    states.remove(id);
    pending.remove(id);
    viewers.remove(id);
    chat.remove(id);
    retry.remove(id);
    attempts.remove(id);
    tab.forget(id);
    nameTags.forget(id);
    requestAll();
  }

  public void request(UUID id) {
    if (online.contains(id)) pending.add(id);
  }

  public void requestViewer(UUID id) {
    if (online.contains(id)) viewers.add(id);
  }

  public void requestAll() {
    all.set(true);
  }

  public ChatPrefix chat(UUID id) {
    return !open || !settings.prefixEnabled || !settings.chatEnabled
        ? new ChatPrefix("", "", null)
        : chat.getOrDefault(id, new ChatPrefix("", "", null));
  }

  public String forViewer(ChatPrefix prefix,UUID viewer){
    boolean animate=animatedViewer.test(viewer);StringBuilder out=new StringBuilder();
    if(prefix.asset==null)out.append(animate?prefix.text:prefix.frozenText);else if(delivery.has(viewer,prefix.asset.id))out.append(animate?prefix.graphic:prefix.frozenGraphic);else if(settings.features.fallback)out.append(animate?prefix.text:prefix.frozenText);
    for(ChatPrefix.Segment p:prefix.parts){if(p.asset==null)out.append(animate?p.text:p.frozenText);else if(delivery.has(viewer,p.asset.id))out.append(animate?p.graphic:p.frozenGraphic);else if(settings.features.fallback)out.append(animate?p.text:p.frozenText);}return out.toString();
  }

  private void drain() {
    if (!open) return;
    tick++;
    Set<UUID> changed = new HashSet<>();
    boolean refresh = all.getAndSet(false);
    if (refresh)
      for (Player p : plugin.getServer().getOnlinePlayers()) pending.add(p.getUniqueId());
    retry
        .entrySet()
        .removeIf(
            e -> {
              if (e.getValue() <= tick) {
                pending.add(e.getKey());
                return true;
              }
              return false;
            });
    Set<UUID> batch = new HashSet<>(pending);
    pending.removeAll(batch);
    for (UUID id : batch) {
      Player p = plugin.getServer().getPlayer(id);
      if (p == null || !p.isOnline()) {
        forget(id);
        continue;
      }
      try {
        refresh(p);
        changed.add(id);
      } catch (RuntimeException e) {
        plugin.getLogger().warning("Cannot refresh prefix: " + FailuresMessage(e));
      }
    }
    if (tick % settings.features.period == 0)
      for (Map.Entry<UUID, State> e : states.entrySet()) {
        State s = e.getValue();
        boolean textChanged = false;
        if (s.source != null && !s.source.animation.equals("NONE")) {
          String nextText = renderPrefix(s.source,s.variables,true);
          textChanged = !nextText.equals(s.text);s.text=nextText;
        }
        boolean partsAnimated=s.style!=null&&s.style.prefixParts.stream().anyMatch(p->p.enabled&&(!p.line.animation.equals("NONE")||imageAnimated(p.image)));
        String next = combine(s);
        if (textChanged || partsAnimated || !next.equals(s.graphic)) {
          s.graphic = next;
          publish(e.getKey(), s);
          changed.add(e.getKey());
        }
      }
    Set<UUID> dirtyViewers = new HashSet<>(viewers);
    viewers.removeAll(dirtyViewers);
    boolean periodic = tick % 100 == 0;
    if (!managedDisplays && refresh && (!settings.prefixEnabled || !settings.tabEnabled))
      restorePackets();
    if (!managedDisplays && settings.prefixEnabled && settings.tabEnabled) {
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        boolean complete = refresh || periodic || dirtyViewers.contains(viewer.getUniqueId());
        Map<Player, String> names = new LinkedHashMap<>();
        for (Map.Entry<UUID, State> e : states.entrySet())
          if (complete || changed.contains(e.getKey())) {
            Player target = plugin.getServer().getPlayer(e.getKey());
            if (target != null && viewer.canSee(target)) {
              State s = e.getValue();
              String prefix = prefix(target,viewer);
              names.put(target, prefix + "§r" + target.getName());
            }
          }
        packets.send(viewer, names);
      }
    }
    if (settings.prefixEnabled
        && settings.features.nameTags
        && !managedDisplays
        && (!changed.isEmpty() || refresh || periodic || !dirtyViewers.isEmpty())) tags();
    if (!changed.isEmpty() || refresh || !dirtyViewers.isEmpty()) displayChanged.run();
  }

  private static String FailuresMessage(RuntimeException e) {
    return me.snowsun.tabprefix.util.Failures.message(e);
  }

  private void refresh(Player p) {
    UUID id = p.getUniqueId();
    if (!settings.prefixEnabled) {
      states.remove(id);
      chat.remove(id);
      tab.restore(p);
      nameTags.close();
      return;
    }
    PlayerIdentity identity = luck.identity(p, settings.usePrimaryGroup);
    if (identity == null) {
      int n = attempts.getOrDefault(id, 0) + 1;
      attempts.put(id, n);
      if (n <= 20) retry.put(id, tick + 5);
      return;
    }
    attempts.remove(id);
    retry.remove(id);
    GroupTextPrefix custom = texts.find(identity.group);
    String text =
        renderer.prefix(
            custom == null ? identity.luckPermsPrefix : custom.text(),
            custom == null ? TextFormat.LEGACY : custom.format());
    AssetDescriptor asset = graphics.snapshot().asset(identity.group);
    DisplayDesign display = design.get();
    DisplayDesign.GroupStyle rule = display == null ? null : display.groupStyle(identity.group);
    Map<String,String> vars = new LinkedHashMap<>();
    vars.put("player",p.getName());vars.put("group",identity.group);vars.put("world",p.getWorld().getName());
    vars.put("server",display==null?"Server":display.serverName);
    if (rule != null && !rule.prefixMode.equals("INHERIT")) {
      text = rule.prefixMode.equals("NONE") ? "" : renderPrefix(rule.prefix,vars,true);
      asset = rule.prefixMode.equals("NONE") || rule.prefixImage==null ? null : graphics.snapshot().assets.get(rule.prefixImage);
    }
    State s =
        new State(
            separate(text),
            asset,
            identity.group,
            renderer.prefix(identity.luckPermsSuffix, TextFormat.LEGACY),
            identity.groupWeight);
    s.style=rule;s.variables=vars;
    if (rule != null && rule.prefixMode.equals("CUSTOM")) { s.source=rule.prefix;s.variables=vars; }
    s.graphic = combine(s);
    states.put(id, s);
    publish(id, s);
    if (!managedDisplays && settings.tabEnabled)
      tab.apply(p, s.asset != null && !settings.features.fallback ? "" : s.text);
    else tab.restore(p);
  }

  private String renderPrefix(DisplayDesign.Line line,Map<String,String> vars,boolean animate) {
    String rendered=renderer.legacy(renderer.display(line.source(tick,animate),vars));
    if (animate) rendered=DisplayAnimation.animate(rendered,line,tick);
    return separate(rendered);
  }

  private String separate(String text) {
    return text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1))
        ? text
        : text + settings.separator;
  }

  private String combine(State state) {
    return combine(state, tick);
  }

  private String combine(State state, long animationTick) {
    if (state.asset == null) return state.text;
    String glyph = "§f" + state.asset.glyph(settings.features.animation ? animationTick : 0) + "§r";
    return settings.features.imageFirst
        ? separate(glyph) + state.text
        : state.text + separate(glyph);
  }

  private void publish(UUID id, State state) {
    String frozen=state.source==null?state.text:renderPrefix(state.source,state.variables,false);
    String graphic=combine(new State(frozen,state.asset,state.group),0);
    List<ChatPrefix.Segment> parts=new ArrayList<>();if(state.style!=null)for(DisplayDesign.AffixPart p:state.style.prefixParts)if(p.enabled){String value=TabFormatting.partText(p,state.variables,renderer,tick,true),still=TabFormatting.partText(p,state.variables,renderer,0,false),gap=TabFormatting.gap(p.gap);AssetDescriptor image=p.image==null?null:graphics.snapshot().assets.get(p.image);String picture=image==null||!image.assigned()?"":"§f"+image.glyph(settings.features.animation?tick:0)+"§r";String first=image==null||!image.assigned()?"":"§f"+image.glyph(0)+"§r";parts.add(new ChatPrefix.Segment(value+gap,picture+value+gap,still+gap,first+still+gap,image));}
    chat.put(id, new ChatPrefix(state.text, state.graphic, state.asset,frozen,graphic,parts));
  }

  private void tags() {
    Map<Scoreboard, List<Player>> boards = new IdentityHashMap<>();
    for (Player viewer : plugin.getServer().getOnlinePlayers())
      boards
          .computeIfAbsent(
              nameTags.board(viewer, settings.features.ownScoreboard), k -> new ArrayList<>())
          .add(viewer);
    nameTags.retain(boards.keySet());
    for (Map.Entry<Scoreboard, List<Player>> board : boards.entrySet())
      for (Map.Entry<UUID, State> e : states.entrySet()) {
        Player target = plugin.getServer().getPlayer(e.getKey());
        if (target == null) continue;
        State s = e.getValue();
        boolean loaded = s.asset != null;
        for (Player viewer : board.getValue())
          loaded &= s.asset != null && delivery.has(viewer.getUniqueId(), s.asset.id);
        nameTags.apply(
            board.getKey(),
            target,
            loaded ? s.graphic : s.asset != null && !settings.features.fallback ? "" : s.text);
      }
  }

  private void restorePackets() {
    if (!managedDisplays)
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        Map<Player, String> names = new LinkedHashMap<>();
        for (Player target : plugin.getServer().getOnlinePlayers())
          if (viewer.canSee(target)) names.put(target, target.getPlayerListName());
        packets.send(viewer, names);
      }
  }

  @Override
  public void close() {
    open = false;
    if (task != null) task.cancel();
    for (Player p : plugin.getServer().getOnlinePlayers()) tab.restore(p);
    if (!managedDisplays)
      for (Player viewer : plugin.getServer().getOnlinePlayers()) {
        Map<Player, String> names = new LinkedHashMap<>();
        for (Player target : plugin.getServer().getOnlinePlayers())
          if (viewer.canSee(target)) names.put(target, target.getPlayerListName());
        packets.send(viewer, names);
      }
    nameTags.close();
    online.clear();
    pending.clear();
    viewers.clear();
    states.clear();
    chat.clear();
    retry.clear();
    attempts.clear();
  }
}
