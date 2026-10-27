package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.function.*;
import me.snowsun.tabprefix.domain.DisplayDesign;
import me.snowsun.tabprefix.domain.TextFormat;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import org.bukkit.entity.Player;

/** Shared deterministic sorting and suffix composition; no permission or database reads. */
public final class TabFormatting {
  private TabFormatting() {}

  public static Comparator<Player> order(
      DisplayDesign d,
      Function<Player, String> group,
      ToIntFunction<Player> weight,
      ToIntFunction<Player> ping) {
    Comparator<Player> names =
        Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Player::getUniqueId);
    Comparator<Player> groups = Comparator.comparing(group, String.CASE_INSENSITIVE_ORDER);
    Comparator<Player> comparator;
    switch (d.sort) {
      case "GROUP":
        comparator = groups.thenComparing(names);
        break;
      case "PRIORITY":
        comparator =
            Comparator.comparingInt((Player p) -> d.groupRank(group.apply(p)))
                .thenComparing(Comparator.comparingInt(weight).reversed())
                .thenComparing(groups)
                .thenComparing(names);
        break;
      case "WEIGHT":
        comparator =
            Comparator.comparingInt(weight).reversed().thenComparing(groups).thenComparing(names);
        break;
      case "PING":
        comparator = Comparator.comparingInt(ping).thenComparing(names);
        break;
      case "WORLD":
        comparator =
            Comparator.comparing(
                    (Player p) -> p.getWorld().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(names);
        break;
      default:
        comparator = names;
    }
    return d.sortReverse ? comparator.reversed() : comparator;
  }

  public static String line(
      DisplayDesign.Line line,
      Map<String, String> vars,
      TextRenderer renderer,
      long tick,
      boolean animate) {
    return line(line, TextFormat.MINIMESSAGE, vars, renderer, tick, animate);
  }

  public static String line(DisplayDesign.Line line, TextFormat format, Map<String, String> vars,
      TextRenderer renderer, long tick, boolean animate) {
    String source = line.source(tick, animate);
    String legacy = renderer.legacy(format == TextFormat.LEGACY
        ? renderer.displayLegacy(source, vars) : renderer.display(source, vars));
    return animate ? DisplayAnimation.animate(legacy,line,tick) : legacy;
  }

  public static String suffix(
      DisplayDesign d,
      DisplayDesign.GroupStyle style,
      String luckSuffix,
      Map<String, String> vars,
      TextRenderer renderer,
      long tick,
      boolean enabled,
      boolean animate) {
    if (!enabled || !d.suffixEnabled) return "";
    String suffix =
        style == null
            ? (d.luckSuffixFallback ? luckSuffix : "")
            : style.suffixMode.equals("NONE")
                ? ""
                : style.suffixMode.equals("LUCKPERMS")
                    ? luckSuffix
                    : line(style.suffix, style.suffixFormat, vars, renderer, tick, animate);
    String visible = suffix.replaceAll("§[0-9a-fklmnorxA-FKLMNORX]", "");
    if (visible.isEmpty()) return "";
    return "§r" + (Character.isWhitespace(visible.charAt(0)) ? "" : " ") + suffix;
  }

  public static String gap(int amount){StringBuilder s=new StringBuilder();for(int n=0;n<amount;n++)s.append(' ');return s.toString();}
  public static String partText(DisplayDesign.AffixPart part,Map<String,String> vars,TextRenderer renderer,long tick,boolean animate){
    String source=part.line.source(tick,animate),legacy=renderer.legacy(part.format==me.snowsun.tabprefix.domain.TextFormat.LEGACY?renderer.displayLegacy(source,vars):renderer.display(source,vars));
    return animate?DisplayAnimation.animate(legacy,part.line,tick):legacy;
  }
  public static String parts(List<DisplayDesign.AffixPart> parts,Map<String,String> vars,TextRenderer renderer,long tick,boolean animate,Function<UUID,String> images){
    StringBuilder out=new StringBuilder();for(DisplayDesign.AffixPart p:parts)if(p.enabled){String image=p.image==null?"":images.apply(p.image),text=partText(p,vars,renderer,tick,animate);if(!image.isEmpty()||!text.isEmpty())out.append(image).append(text).append("§r").append(gap(p.gap));}return out.toString();
  }
  public static String suffix(DisplayDesign d,DisplayDesign.GroupStyle style,String luckSuffix,Map<String,String> vars,TextRenderer renderer,long tick,boolean enabled,boolean animate,Function<UUID,String> images){
    if(!enabled||!d.suffixEnabled)return "";
    String value=suffix(d,style,luckSuffix,vars,renderer,tick,enabled,animate);
    if(style==null)return value;String image=style.suffixImage==null?"":images.apply(style.suffixImage);
    String more=parts(style.suffixParts,vars,renderer,tick,animate,images);
    return value+(image.isEmpty()?"":" §r"+image)+(more.isEmpty()?"":" §r"+more);
  }

  public static String name(String player, DisplayDesign.GroupStyle style, TextRenderer renderer) {
    if (style == null || style.nameColor.equals("DEFAULT")) return player;
    return renderer.legacy(
        renderer.display(
            "<" + style.nameColor + ">{player}</" + style.nameColor + ">",
            Collections.singletonMap("player", player)));
  }
}
