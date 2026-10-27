package me.snowsun.tabprefix.infrastructure.bukkit;

import org.bukkit.Server;
import me.snowsun.tabprefix.domain.DisplayDesign;

/** Vanilla shows faces on an encrypted connection. Proxies may need the explicit editor override. */
public final class TabGeometry {
  private TabGeometry() {}
  public static boolean heads(Server server,DisplayDesign d) {
    if(!d.tabHeads.equals("AUTO"))return d.tabHeads.equals("ON");
    if(server.getOnlineMode())return true;
    try {if(server.spigot().getConfig().getBoolean("settings.bungeecord",false))return true;}catch(RuntimeException|LinkageError ignored){}
    try {
      Class<?> c=Class.forName("io.papermc.paper.configuration.GlobalConfiguration");Object config=c.getMethod("get").invoke(null);
      Object proxies=config.getClass().getField("proxies").get(config),velocity=proxies.getClass().getField("velocity").get(proxies);
      return velocity.getClass().getField("enabled").getBoolean(velocity);
    }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){return false;}
  }
}
