package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;
import me.snowsun.tabprefix.util.Digests;

/** The effective prefix, including a Studio rule, has one optimistic content revision. */
public final class PrefixEdit {
  private PrefixEdit() {}

  public static GroupTextPrefix text(String group, GroupTextPrefix stored, DisplayDesign design) {
    DisplayDesign.GroupStyle rule=design==null?null:design.groupStyle(group);
    if(rule==null||rule.prefixMode.equals("INHERIT"))return stored;
    String source=rule.prefixMode.equals("NONE")?"":rule.prefix.source(0,false);
    return source.trim().isEmpty()?null:new GroupTextPrefix(group,source,source.matches("(?s).*&[0-9a-fk-orA-FK-OR].*")&&!source.contains("<")?TextFormat.LEGACY:TextFormat.MINIMESSAGE,new UUID(0,0),0);
  }

  public static UUID image(String group,GraphicSnapshot graphics,DisplayDesign design) {
    DisplayDesign.GroupStyle rule=design==null?null:design.groupStyle(group);
    if(rule!=null&&!rule.prefixMode.equals("INHERIT"))return rule.prefixMode.equals("NONE")?null:rule.prefixImage;
    AssetDescriptor asset=graphics.asset(group);return asset==null?null:asset.id;
  }

  public static String revision(String group,GraphicSnapshot graphics,GroupTextPrefix stored,DisplayDesign design) {
    JsonObject j=new JsonObject();j.addProperty("group",group);
    UUID image=image(group,graphics,design);j.addProperty("image",image==null?"":image.toString());
    GroupTextPrefix text=text(group,stored,design);j.addProperty("override",text!=null);
    j.addProperty("text",text==null?"":text.text());j.addProperty("format",text==null?"MINIMESSAGE":text.format().name());
    DisplayDesign.GroupStyle rule=design==null?null:design.groupStyle(group);
    if(rule!=null&&!rule.prefixMode.equals("INHERIT")){j.addProperty("mode",rule.prefixMode);j.add("animation",rule.prefix.json());}
    if(rule!=null){JsonObject r=rule.json();for(String key:new String[]{"prefixParts","suffixParts","suffix","suffixMode","suffixImage","suffixFormat"})j.add(key,r.get(key));}
    return Digests.sha256(j.toString());
  }

  /** A single-group edit keeps every other group's shared rule, suffix and name color. */
  public static DisplayDesign storedPrefix(DisplayDesign design,String group,UUID image,String text,TextFormat format) {
    if(design==null)return null;
    DisplayDesign.GroupStyle style=design.groupStyle(group);
    if(style==null||style.prefixMode.equals("INHERIT"))return null;
    JsonObject doc=design.json();JsonArray rules=doc.getAsJsonArray("groupStyles"),out=new JsonArray();
    for(JsonElement e:rules){
      JsonObject rule=e.getAsJsonObject();
      if(!rule.get("group").getAsString().equals(style.group)){out.add(rule);continue;}
      JsonObject own=new JsonParser().parse(rule.toString()).getAsJsonObject();
      own.addProperty("group",group);JsonArray target=new JsonArray();target.add(group);own.add("groups",target);
      if(style.prefixMode.equals("CUSTOM")&&text!=null){
        own.addProperty("prefixMode","CUSTOM");own.addProperty("prefixImage",image==null?"":image.toString());
        JsonObject line=own.getAsJsonObject("prefix");line.addProperty("text",text);
        if(style.prefix.animation.equals("FRAMES")){JsonArray frames=new JsonArray();frames.add(text);for(int n=1;n<style.prefix.frames.size();n++)frames.add(style.prefix.frames.get(n));line.add("frames",frames);}
      }else own.addProperty("prefixMode","INHERIT");
      if(style.groups.size()>1){
        if(rules.size()>=64)throw new IllegalArgumentException("Split the shared prefix rule in Display Studio before editing this group; the 64-rule limit is reached.");
        JsonArray remaining=new JsonArray();for(String name:style.groups)if(!name.equals(group))remaining.add(name);
        rule.addProperty("group",remaining.get(0).getAsString());rule.add("groups",remaining);out.add(rule);
      }
      out.add(own);
    }
    doc.add("groupStyles",out);return new DisplayDesign(doc);
  }
}
