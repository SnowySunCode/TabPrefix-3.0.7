package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.domain.BossBinding;
import org.bukkit.*;
import org.bukkit.entity.*;

/** Loaded entities only; no chunk loads. Each world is scanned once per display tick. */
public final class BossEntityResolver {
  private long cacheTick = Long.MIN_VALUE;
  private final Map<World,List<LivingEntity>> worlds = new IdentityHashMap<>();
  public LivingEntity find(Player viewer,BossBinding binding,long tick) {
    if (!binding.enabled()) return null;
    if (cacheTick!=tick) { cacheTick=tick;worlds.clear(); }
    World world=viewer.getWorld();
    List<LivingEntity> entities=worlds.computeIfAbsent(world,w->new ArrayList<>(w.getLivingEntities()));
    LivingEntity nearest=null;double best=binding.radius*binding.radius;
    Location origin=viewer.getLocation();
    for(LivingEntity mob:entities) {
      if (mob instanceof Player || mob instanceof ArmorStand || mob.isDead() || !mob.isValid()) continue;
      boolean match;
      switch(binding.mode) {
        case "UUID": match=mob.getUniqueId().toString().equalsIgnoreCase(binding.uuid);break;
        case "TYPE": match=mob.getType().name().equals(binding.entityType);break;
        case "NAME": match=Objects.equals(ChatColor.stripColor(mob.getCustomName()),binding.name);break;
        case "TAG": match=mob.getScoreboardTags().contains(binding.tag);break;
        default: match=true;
      }
      if(!match)continue;
      Location at=mob.getLocation();if(at.getWorld()!=origin.getWorld())continue;
      double distance=at.distanceSquared(origin);
      if(distance<=best && (nearest==null || distance<best || mob.getUniqueId().compareTo(nearest.getUniqueId())<0)) {
        nearest=mob;best=distance;
      }
    }
    return nearest;
  }
  public Map<String,String> values(LivingEntity mob) {
    Map<String,String> vars=new LinkedHashMap<>();
    vars.put("boss_name",mob.getCustomName()==null?mob.getType().name():mob.getCustomName());
    vars.put("boss_type",mob.getType().name());vars.put("boss_uuid",mob.getUniqueId().toString());
    vars.put("boss_health",number(mob.getHealth()));vars.put("boss_max_health",number(mob.getMaxHealth()));
    return vars;
  }
  private static String number(double n) { return java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString(); }
}
