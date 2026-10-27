package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;

/** Detached optional changes to decorations, committed with the base prefix in one SQL transaction. */
public final class PrefixDecoration {
  private final JsonObject changes;
  public PrefixDecoration(JsonObject changes){this.changes=new JsonParser().parse(changes.toString()).getAsJsonObject();}
  public JsonObject json(){return new JsonParser().parse(changes.toString()).getAsJsonObject();}
  public Set<UUID> images(){
    Set<UUID> ids=new LinkedHashSet<>();
    if(changes.has("suffixImage")&&!changes.get("suffixImage").getAsString().isEmpty())ids.add(UUID.fromString(changes.get("suffixImage").getAsString()));
    for(String key:new String[]{"prefixParts","suffixParts"})if(changes.has(key))for(JsonElement e:changes.getAsJsonArray(key)){String id=e.getAsJsonObject().get("image").getAsString();if(!id.isEmpty())ids.add(UUID.fromString(id));}
    return ids;
  }
  public DisplayDesign apply(DisplayDesign current,String group){
    DisplayDesign d=current==null?DisplayDesign.defaults(false):current;JsonObject doc=d.json();JsonArray rules=doc.getAsJsonArray("groupStyles"),out=new JsonArray();
    DisplayDesign.GroupStyle old=d.groupStyle(group);JsonObject own=null;
    if(old!=null){JsonObject row=old.json();boolean same=true;for(Map.Entry<String,JsonElement> e:changes.entrySet())if(!e.getValue().equals(row.get(e.getKey()))){same=false;break;}if(same)return d;}
    if(old==null){JsonObject blank=new JsonObject();blank.addProperty("group",group);blank.addProperty("suffixMode","LUCKPERMS");JsonObject row=new DisplayDesign.GroupStyle(blank).json();boolean same=true;for(Map.Entry<String,JsonElement> e:changes.entrySet())if(!e.getValue().equals(row.get(e.getKey()))){same=false;break;}if(same)return d;}
    for(JsonElement e:rules){JsonObject row=e.getAsJsonObject();if(old==null||!row.get("group").getAsString().equals(old.group)){out.add(row);continue;}
      own=new JsonParser().parse(row.toString()).getAsJsonObject();
      if(old.groups.size()>1){if(rules.size()>=64)throw new IllegalArgumentException("Split the shared group rule before adding individual decorations; 64 rules are already used.");JsonArray left=new JsonArray();for(String name:old.groups)if(!name.equals(group))left.add(name);row.addProperty("group",left.get(0).getAsString());row.add("groups",left);out.add(row);}
    }
    if(own==null){if(out.size()>=64)throw new IllegalArgumentException("Maximum 64 group rules");own=new JsonObject();own.addProperty("suffixMode","LUCKPERMS");}
    own.addProperty("group",group);JsonArray targets=new JsonArray();targets.add(group);own.add("groups",targets);
    for(Map.Entry<String,JsonElement> e:changes.entrySet())own.add(e.getKey(),e.getValue());
    out.add(own);doc.add("groupStyles",out);return new DisplayDesign(doc);
  }
}
