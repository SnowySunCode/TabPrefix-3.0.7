package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.*;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.entity.Player;

/** Uses Bukkit when available and the native header/footer packet on older servers. */
public final class HeaderFooterAccess {
  private final Logger logger;
  private final NativeTabAccess packets = new NativeTabAccess();
  private final Map<UUID, String[]> remembered = new HashMap<>();
  private Method getHeader, getFooter, setHeader, setFooter;
  private boolean failed, warnedRead, nativeWrite, warnedWrite, nativeCanvasUnavailable;
  private final boolean cleanCanvas;
  private final Map<UUID,boolean[]> shadowReads=new HashMap<>();

  public HeaderFooterAccess(Logger logger, MinecraftVersion version) {
    this.logger = logger;
    cleanCanvas=version.compareTo(MinecraftVersion.parse("1.21.4"))>=0;
    // On 1.12 Paper-specific send methods have different signatures; use the vanilla packet.
    if (version.compareTo(MinecraftVersion.parse("1.13")) >= 0)
      try {
        getHeader = Player.class.getMethod("getPlayerListHeader");
        getFooter = Player.class.getMethod("getPlayerListFooter");
        setHeader = Player.class.getMethod("setPlayerListHeader", String.class);
        setFooter = Player.class.getMethod("setPlayerListFooter", String.class);
      } catch (NoSuchMethodException e) {
        getHeader = null;
      }
  }

  public String header(Player player) {
    return get(player, true);
  }

  public String footer(Player player) {
    return get(player, false);
  }

  private String get(Player player, boolean header) {
    if (getHeader != null && !shadow(player.getUniqueId(),header))
      try {
        String value=(String)(header?getHeader:getFooter).invoke(player);remember(player,header,value);return value;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        markShadow(player.getUniqueId(),header);if(!warnedRead){warnedRead=true;logger.log(Level.WARNING,"Header/footer getter unavailable; retaining the write API and observed values.",e);}
      }
    String[] pair = remembered.get(player.getUniqueId());
    return pair == null ? null : pair[header ? 0 : 1];
  }

  public void header(Player player, String text) {
    set(player, true, text);
  }

  public void footer(Player player, String text) {
    set(player, false, text);
  }

  public boolean writeHeader(Player player,String text){return set(player,true,text);}
  public boolean writeFooter(Player player,String text){return set(player,false,text);}

  private boolean set(Player player, boolean header, String text) {
    try {
      if(cleanCanvas&&!nativeCanvasUnavailable&&NativeTabAccess.canvasText(text))try{
        get(player,!header);String[] old=remembered.getOrDefault(player.getUniqueId(),new String[2]);
        packets.headerFooter(player,header?text:old[0],header?old[1]:text);remember(player,header,text);markShadow(player.getUniqueId(),true);markShadow(player.getUniqueId(),false);failed=false;nativeWrite=true;return true;
      }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){nativeCanvasUnavailable=true;}
      if (getHeader != null) {
        try {(header?setHeader:setFooter).invoke(player,text);remember(player,header,text);failed=false;nativeWrite=false;return true;}
        catch(ReflectiveOperationException|RuntimeException|LinkageError error){markShadow(player.getUniqueId(),header);}
      }
      {
        get(player,!header);
        String[] old = remembered.getOrDefault(player.getUniqueId(), new String[2]);
        packets.headerFooter(player, header ? text : old[0], header ? old[1] : text);
        String[] next = old.clone();
        next[header ? 0 : 1] = text;
        remembered.put(player.getUniqueId(), next);failed=false;nativeWrite=true;return true;
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      fail(e);return false;
    }
  }

  private boolean shadow(UUID id,boolean header){boolean[] pair=shadowReads.get(id);return pair!=null&&pair[header?0:1];}
  private void markShadow(UUID id,boolean header){shadowReads.computeIfAbsent(id,key->new boolean[2])[header?0:1]=true;}

  private void remember(Player player,boolean header,String text){String[] pair=remembered.getOrDefault(player.getUniqueId(),new String[2]).clone();pair[header?0:1]=text;remembered.put(player.getUniqueId(),pair);}

  private void fail(Throwable e) {
    if (!warnedWrite) logger.log(Level.WARNING, "Header/footer adapter unavailable.", e);
    failed = true;warnedWrite=true;
  }

  public void forget(UUID id) {
    remembered.remove(id);shadowReads.remove(id);
  }

  public void clear() {
    remembered.clear();shadowReads.clear();
  }

  public String diagnostic() {
    return "headerFooter="
        + (failed ? "unavailable" : getHeader == null || nativeWrite ? "native" : !shadowReads.isEmpty() ? "bukkit-shadow" : "bukkit");
  }
}
