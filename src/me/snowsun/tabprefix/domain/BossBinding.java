package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;

/** Selects an existing living entity. This does not spawn or modify a mob. */
public final class BossBinding {
  public final String mode, uuid, entityType, name, tag;
  public final double radius;
  public BossBinding(JsonObject j) {
    mode = DisplayDesign.choice(j,"mode","NONE","NONE","UUID","TYPE","NAME","TAG","NEAREST");
    uuid = DisplayDesign.string(j,"uuid","",36);
    entityType = DisplayDesign.string(j,"entityType","WITHER",64).toUpperCase(Locale.ROOT);
    name = DisplayDesign.string(j,"name","",128); tag = DisplayDesign.string(j,"tag","",128);
    radius = DisplayDesign.decimal(j,"radius",64,1,256);
    if (!entityType.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("boss.entityType: invalid type");
    if (mode.equals("UUID")) {
      try { if (!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) throw new IllegalArgumentException(); }
      catch (IllegalArgumentException e) { throw new IllegalArgumentException("boss.uuid: enter a complete entity UUID"); }
    }
    if (mode.equals("NAME") && name.trim().isEmpty()) throw new IllegalArgumentException("boss.name: enter a mob name");
    if (mode.equals("TAG") && tag.trim().isEmpty()) throw new IllegalArgumentException("boss.tag: enter a scoreboard tag");
  }
  public boolean enabled() { return !mode.equals("NONE"); }
  public JsonObject json() {
    JsonObject j = new JsonObject();j.addProperty("mode",mode);j.addProperty("uuid",uuid);
    j.addProperty("entityType",entityType);j.addProperty("name",name);j.addProperty("tag",tag);
    j.addProperty("radius",radius);return j;
  }
}
