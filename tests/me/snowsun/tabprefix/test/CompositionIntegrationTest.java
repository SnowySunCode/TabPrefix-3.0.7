package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import org.bukkit.configuration.file.YamlConfiguration;

/** New behavior is verified through actual SQL, media, font providers and public rendering helpers. */
public final class CompositionIntegrationTest {
  static int checks;static final UUID OWNER=UUID.fromString("11111111-0000-0000-0000-000000000001");
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(30,TimeUnit.SECONDS);}
  static JsonObject line(String text){JsonObject j=new JsonObject();j.addProperty("text",text);return j;}
  static JsonObject part(String id,String text,UUID image){JsonObject j=new JsonObject();j.addProperty("id",id);j.addProperty("enabled",true);j.addProperty("image",image==null?"":image.toString());j.addProperty("format","MINIMESSAGE");j.addProperty("gap",1);j.add("line",line(text));return j;}
  static byte[] png()throws Exception{BufferedImage image=new BufferedImage(24,8,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<8;y++)for(int x=0;x<24;x++)image.setRGB(x,y,0xff3399cc);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
  static EditorOptions options(EditorOptions.Fit fit,int width,int height,int x,int y){return new EditorOptions(0,0,0,0,fit,true,false,false,false,0,16,13,0,1,1,width,height,x,y,0,0);}
  public static void main(String[] args)throws Exception {
    Path project=Paths.get(args[0]),root=Files.createTempDirectory(project.resolve(".build/test-data"),"composition-");YamlConfiguration yaml=YamlConfiguration.loadConfiguration(project.resolve("resources/config.yml").toFile());PluginSettings cfg=new PluginSettings(yaml,root,MinecraftVersion.parse("1.21.11"));
    Path database=cfg.databaseFile;UUID imageId;DisplayDesign saved;
    try(SqliteDatabase db=new SqliteDatabase(database,Logger.getLogger("CompositionTest"))){await(db.initialize());PrefixService texts=new PrefixService(new SqliteGroupPrefixRepository(db));await(texts.initialize());GraphicService graphics=new GraphicService(new SqliteGraphicRepository(db),texts,cfg.features);await(graphics.initialize());DisplayService display=new DisplayService(db,false);await(display.initialize());MediaStore media=new MediaStore(cfg);
      JsonObject doc=display.snapshot().design.json(),rule=new JsonObject();rule.addProperty("group","vip");JsonArray groups=new JsonArray();groups.add("vip");groups.add("mod");rule.add("groups",groups);rule.addProperty("prefixMode","CUSTOM");rule.add("prefix",line("<gold>[VIP]</gold>"));rule.add("suffix",line("[old]"));rule.addProperty("nameColor","#aabbcc");JsonArray rules=new JsonArray();rules.add(rule);doc.add("groupStyles",rules);await(display.save(new DisplayDesign(doc),display.snapshot().revision,OWNER));
      byte[] original=png();EditorOptions natural=options(EditorOptions.Fit.ORIGINAL,0,0,50,50);ProcessedMedia nativeImage=new ImageDecoder().decode(original,natural,cfg.features);check(nativeImage.frames.get(0).getWidth()==24&&nativeImage.frames.get(0).getHeight()==8,"native dimensions preserve the source ratio");AssetDescriptor image=media.store(original,nativeImage,natural);await(graphics.register(image));imageId=image.id;
      JsonObject changes=new JsonObject();JsonArray prefixes=new JsonArray();prefixes.add(part("extra-prefix","<aqua>[TEAM]</aqua>",null));changes.add("prefixParts",prefixes);JsonArray suffixes=new JsonArray();suffixes.add(part("suffix-image","",image.id));suffixes.add(part("suffix-text","<green>[EVENT]</green>",null));changes.add("suffixParts",suffixes);changes.addProperty("suffixMode","CUSTOM");changes.add("suffix",line("[BUILD]"));changes.addProperty("suffixImage","");PrefixDecoration decoration=new PrefixDecoration(changes);
      String revision=PrefixEdit.revision("vip",graphics.snapshot(),texts.find("vip"),display.snapshot().design);PrefixEditCommit commit=await(graphics.edit(OWNER,"vip",null,"<gold>[VIP]</gold>",TextFormat.MINIMESSAGE,revision,decoration,display::acceptPrefixEdit));check(commit.changed,"compound edit performs one real commit");saved=display.snapshot().design;check(saved.groupStyle("vip").prefixParts.size()==1&&saved.groupStyle("vip").suffixParts.size()==2,"ordered decorations are persisted");check(saved.groupStyle("mod").prefixParts.isEmpty()&&saved.groupStyle("mod").suffix.text.equals("[old]"),"editing one group preserves the other shared rule");check(saved.groupStyle("vip").nameColor.equals("#aabbcc"),"compound edit preserves name color");check(graphics.snapshot().assets.get(image.id).assigned(),"suffix picture receives real permanent glyphs");
      String current=PrefixEdit.revision("vip",graphics.snapshot(),texts.find("vip"),saved);PrefixEditCommit repeat=await(graphics.edit(OWNER,"vip",null,"<gold>[VIP]</gold>",TextFormat.MINIMESSAGE,current,decoration,display::acceptPrefixEdit));check(!repeat.changed,"an unchanged composition does not create new revisions");
      BufferedImage tiny=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<2;y++)for(int x=0;x<2;x++)tiny.setRGB(x,y,0xffffffff);BufferedImage left=ImageTransform.transform(tiny,options(EditorOptions.Fit.CONTAIN,20,10,0,0),cfg.features),right=ImageTransform.transform(tiny,options(EditorOptions.Fit.CONTAIN,20,10,100,100),cfg.features);check(left.getRGB(0,0)!=0&&left.getRGB(19,9)==0,"top-left image anchor changes actual pixels");check(right.getRGB(19,9)!=0&&right.getRGB(0,0)==0,"bottom-right image anchor changes actual pixels");
      JsonObject layoutDoc=saved.json();layoutDoc.addProperty("layoutEnabled",true);layoutDoc.addProperty("sidebarEnabled",true);layoutDoc.addProperty("sidebarMode","HUD");JsonObject box=new JsonObject();box.addProperty("surface","CELL");box.addProperty("index",0);box.addProperty("width",48);box.addProperty("height",8);JsonArray boxes=new JsonArray();boxes.add(box);layoutDoc.getAsJsonObject("artwork").add("boxes",boxes);DisplayDesign design=new DisplayDesign(layoutDoc);PackBuilder builder=new PackBuilder(cfg,media,cfg.minecraft,"http://127.0.0.1:8999");builder.designs(()->design);PackRevision pack=builder.build(graphics.snapshot());NativeHudLayout.Profile background=pack.hud.get("tab:cell:0",0,8,design.artwork.signature(design,design.artwork.box("CELL",0)));check(background!=null&&background.frames()>1,"TAB artwork includes prebuilt wider vector variants");check(pack.assets.contains(image.id)&&pack.hud.foreignWidths.containsKey(graphics.snapshot().assets.get(image.id).glyphCode(0)),"the pack includes independently assigned suffix images and metrics");
      String longName="§f"+graphics.snapshot().assets.get(image.id).glyph(0)+" §fLong player name [EVENT]";String overlay=ArtworkText.tab(pack.hud,design,"CELL",0,longName);check(java.util.stream.IntStream.range(1,background.frames()).anyMatch(n->overlay.contains(background.glyph(n))),"a long decorated name chooses a wider TAB background");
      NativeHudLayout.Profile font=pack.hud.get("tab:text",0,8,"");Set<Character> seen=new HashSet<>();String letters="Длинная строка со всеми буквами";for(int tick=0;tick<1200;tick+=6){NativeHudLayout.Encoded frame=DisplayAnimation.fit(font,"§a"+letters,Collections.emptyMap(),30,"SCROLL",tick,true);check(frame.width<=30,"pixel-aware overflow stays inside the resized panel");frame.text.codePoints().filter(c->c>=font.base&&c<font.base+font.frames()).forEach(c->seen.add(NativeHudLayout.ALPHABET.charAt(c-font.base)));}for(char c:letters.toCharArray())if(c!=' ')check(seen.contains(c),"overflow eventually displays every letter: "+c);
      NativeHudLayout.Profile narrowFont=pack.hud.get("tab:text",0,8,"");
      NativeHudLayout.Encoded wideGlyph=DisplayAnimation.fit(narrowFont,"§aW",Collections.emptyMap(),1,"SCROLL",0,true);
      check(wideGlyph.text.codePoints().anyMatch(code -> code == narrowFont.base+NativeHudLayout.ALPHABET.indexOf('W')), "a glyph wider than the viewport is shown whole instead of disappearing");
      check(pack.hud.profiles.containsKey("sidebar:14"), "spare scoreboard row profiles are ready before a new line is added");
      JsonObject oldRenderer=pack.hud.json();oldRenderer.remove("rendererVersion");
      check(!NativeHudLayout.read(oldRenderer).matches(design), "cached packs made by an older renderer are rebuilt");
      TextRenderer renderer=new TextRenderer(cfg);
      JsonObject legacyStyle = new JsonObject(); legacyStyle.addProperty("group", "legacy");
      legacyStyle.addProperty("suffixMode", "CUSTOM"); legacyStyle.addProperty("suffixFormat", "LEGACY");
      legacyStyle.add("suffix", line("&a<gold>suffix"));
      check(TabFormatting.suffix(design, new DisplayDesign.GroupStyle(legacyStyle), "", Collections.emptyMap(), renderer, 0, true, false).contains("<gold>suffix"), "base suffix supports its selected legacy format without interpreting literal tags");
      check(renderer.plain(renderer.displayLegacy("<gold>{player}",Collections.singletonMap("player","<red>literal"))).equals("<gold><red>literal"),"legacy affix format preserves literal tags and safe placeholders");for(String effect:new String[]{"BOUNCE","TYPEWRITER","SHIMMER","WAVE","PULSE","BLINK"}){JsonObject raw=line("ABCDEF");raw.addProperty("animation",effect);raw.addProperty("speed",2);raw.addProperty("width",4);DisplayDesign.Line l=new DisplayDesign.Line(raw);Set<String> frames=new HashSet<>();for(int tick=0;tick<80;tick+=2)frames.add(DisplayAnimation.animate("§aABCDEF",l,tick));check(frames.size()>1,"effect actually animates: "+effect);}
      List<VirtualTabPackets.Cell> cells=new ArrayList<>(Collections.nCopies(80,new VirtualTabPackets.Cell("",null)));cells.set(0,new VirtualTabPackets.Cell("Tall image",null));cells.set(1,new VirtualTabPackets.Cell("Next row",null));int[] above=new int[80],below=new int[80],widths=new int[80];above[0]=1;below[0]=2;AdaptiveTabLayout.Result fitted=AdaptiveTabLayout.arrange(cells,above,below,widths,10,Collections.emptyMap(),true);check(fitted.fitted&&fitted.source[1]==0&&fitted.source[4]==1,"adaptive TAB reserves rows around a tall image");for(int n=0;n<20;n++)cells.set(n,new VirtualTabPackets.Cell("Cell "+n,null));AdaptiveTabLayout.Result crowded=AdaptiveTabLayout.arrange(cells,above,below,widths,0,Collections.emptyMap(),false);check(!crowded.fitted&&Arrays.stream(crowded.source).allMatch(i->i>=0),"a full column never loses a player or custom block");
    }
    try(SqliteDatabase db=new SqliteDatabase(database,Logger.getLogger("CompositionRestart"))){await(db.initialize());DisplayService display=new DisplayService(db,false);await(display.initialize());GraphicSnapshot graphics=await(new SqliteGraphicRepository(db).load());check(display.snapshot().design.groupStyle("vip").suffixParts.size()==2,"suffix composition survives a real database restart");check(graphics.assets.get(imageId).assigned(),"suffix glyph allocation survives restart");}
    System.out.println("PASS: "+checks+" composition checks: real SQL, shared rules, suffix images, dimensions, adaptive TAB artwork, readable overflow, animations and restart.");
  }
}
