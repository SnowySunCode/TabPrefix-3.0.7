package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;

/** Regression: one real font weight per glyph; exact clipping, original coverage and restart metadata. */
public final class TextQualityIntegrationTest {
  static int checks;
  static Font boldFont;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  static long coverage(BufferedImage image,int channel){long sum=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int p=image.getRGB(x,y);if((p>>>24)<=1)continue;int a=channel==8?(p>>>8)&255:p>>>24;if(a>1)sum+=a;}return sum;}
  static Map<Integer,BufferedImage> glyphs(ZipFile zip,NativeHudLayout hud)throws Exception {
    Set<Integer> wanted=new HashSet<>();for(NativeHudLayout.Profile p:hud.profiles.values())if(p.id.startsWith("screen:size"))for(char c:new char[]{'A','j','W','Д','Й','ё','ф'}){int n=NativeHudLayout.ALPHABET.indexOf(c);wanted.add(p.base+n);wanted.add(p.boldBase+n);}
    Map<Integer,BufferedImage> result=new HashMap<>();Map<String,BufferedImage> images=new LinkedHashMap<String,BufferedImage>(8,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,BufferedImage> e){return size()>4;}};
    JsonObject font=HdVersionsIntegrationTest.json(zip,"assets/minecraft/font/default.json");
    for(JsonElement e:font.getAsJsonArray("providers")){
      JsonObject provider=e.getAsJsonObject();if(!provider.has("file"))continue;JsonArray rows=provider.getAsJsonArray("chars");
      for(int y=0;y<rows.size();y++){int[] codes=rows.get(y).getAsString().codePoints().toArray();for(int x=0;x<codes.length;x++)if(wanted.contains(codes[x])){
        String file=provider.get("file").getAsString().replace("tabprefix:","assets/tabprefix/textures/");BufferedImage image=images.get(file);if(image==null){image=ImageIO.read(zip.getInputStream(zip.getEntry(file)));images.put(file,image);}
        int w=image.getWidth()/codes.length,h=image.getHeight()/rows.size();BufferedImage copy=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);copy.setRGB(0,0,w,h,image.getRGB(x*w,y*h,w,h,null,0,w),0,w);result.put(codes[x],copy);
      }}
    }return result;
  }
  static void reference(BufferedImage actual,int size,boolean bold,Font font,char letter,boolean hd){
    int density=hd?4:Math.max(1,actual.getHeight()/40);
    if(!hd)return;
    BufferedImage expected=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);Graphics2D g=expected.createGraphics();
    try{Font weight=(bold?boldFont:font).deriveFont(bold?Font.BOLD:Font.PLAIN,(float)(size*density));g.setFont(weight);g.setColor(Color.WHITE);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.drawGlyphVector(weight.createGlyphVector(g.getFontRenderContext(),new char[]{letter}),128,256);}finally{g.dispose();}
    check(coverage(actual,8)==coverage(expected,24),"complete original font coverage survives SDF packing: "+letter+", size="+size+", bold="+bold);
    boolean outside=false,inside=false;for(int y=3;y<actual.getHeight()-3;y++)for(int x=0;x<actual.getWidth();x++){int p=actual.getRGB(x,y);if((p>>>24)<=1)continue;int distance=(p>>>16)&255;outside|=distance<128;inside|=distance>128;}
    check(outside&&inside,"distance field contains both sides of the original contour");
  }
  public static void main(String[] args)throws Exception {
    Path project=Paths.get(args[0]);Font font;try(InputStream in=TextQualityIntegrationTest.class.getResourceAsStream("/fonts/DejaVuSans.ttf")){font=Font.createFont(Font.TRUETYPE_FONT,in);}
    try(InputStream in=TextQualityIntegrationTest.class.getResourceAsStream("/fonts/DejaVuSans-Bold.ttf")){boldFont=Font.createFont(Font.TRUETYPE_FONT,in);}
    JsonObject doc=HdVersionsIntegrationTest.document();JsonArray screens=doc.getAsJsonArray("screen");
    for(int size:new int[]{4,7,8,12,16,24,32}){JsonObject s=new JsonObject();s.addProperty("id","size"+size);s.addProperty("kind","TEXT");s.addProperty("anchor","FREE_XY");s.addProperty("height",size);s.addProperty("y",-100-size);screens.add(s);}
    JsonArray gpu=new JsonArray();
    for(String version:new String[]{"1.16.5","1.21.11"}){
      PluginSettings cfg=HdVersionsIntegrationTest.settings(Files.createTempDirectory(project.resolve(".build/test-data"),"text-quality-"),MinecraftVersion.parse(version));PackBuilder builder=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://localhost");DisplayDesign design=new DisplayDesign(doc);builder.designs(()->design);PackRevision pack=builder.build(GraphicSnapshot.empty());
      check(builder.load(GraphicSnapshot.empty()).hud.json().equals(pack.hud.json()),"regular and bold profiles survive restart");
      try(ZipFile zip=new ZipFile(pack.file.toFile())){
        Map<Integer,Integer> metrics=HdVersionsIntegrationTest.metrics(zip);Map<Integer,BufferedImage> glyphs=glyphs(zip,pack.hud);
        for(int size:new int[]{4,7,8,12,16,24,32}){
          NativeHudLayout.Profile p=pack.hud.profiles.get("screen:size"+size);check(p.hasBold(),"real bold font exists at every supported text size");
          for(boolean bold:new boolean[]{false,true})for(char c:new char[]{'A','j','W','Д','Й','ё','ф'}){
            int index=NativeHudLayout.ALPHABET.indexOf(c),code=(bold?p.boldBase:p.base)+index;
            check(metrics.get(code)==p.advance(index,bold),"stored width matches the actual glyph provider");
            check(pack.hud.metrics.get(code).equals(metrics.get(code)),"TAB and scoreboard use the delivered font width");
            reference(glyphs.get(code),size,bold,font,c,version.equals("1.21.11"));
          }
          NativeHudLayout.Encoded full=NativeHudLayout.encode(p,"§a§lАпельсинчикДаня §rName §oItalic §lBoldItalic");
          check(!full.text.contains("§l"),"HD bold never asks Minecraft to draw a second offset copy");
          check(full.width==TabMetrics.width(full.text,pack.hud.metrics),"mixed bold/regular/italic text has exact cursor metrics");
          for(int width=1;width<=full.width;width+=Math.max(1,size/2)){
            NativeHudLayout.Encoded clipped=NativeHudLayout.encode(p,"§b§lАпельсинчикДаня Wj",Collections.emptyMap(),width);
            check(clipped.width<=width&&clipped.width==TabMetrics.width(clipped.text,pack.hud.metrics),"resized text clips on whole glyphs with the exact font weight");
          }
          int a=NativeHudLayout.ALPHABET.indexOf('A');
          NativeHudLayout.Encoded reset=NativeHudLayout.encode(p,"§lA§aA§x§f§f§8§8§0§0A");
          int[] codes=reset.text.codePoints().filter(NativeHudLayout::privateGlyph).toArray();
          check(codes.length==3&&codes[0]==p.boldBase+a&&codes[1]==p.base+a&&codes[2]==p.base+a,"legacy and RGB colours reset font weight correctly");
        }
        NativeHudLayout.Profile compact=pack.hud.profiles.get("tab:compact");check(compact.hasBold(),"TAB compact font retains its weight when sharing an atlas");
        NativeHudLayout.Encoded encoded=NativeHudLayout.encode(compact,"§lПривет");check(encoded.text.codePoints().allMatch(NativeHudLayout::privateGlyph),"all-bold TAB text is recognised as a private canvas for shadow suppression");
        String preview=pack.hud.readable(NativeHudLayout.encode(compact,"§a§lА Б§r C").text,id->null);
        check(preview.contains("§lА")&&preview.contains("§lБ"),"web preview decodes real bold glyphs and retains font weight");
        check(preview.replaceAll("§.","").equals("А Б C"),"sub-four-pixel spaces remain visible in the readable TAB preview");
        check(preview.contains("§r§a"),"preview restores the active colour after each bold glyph");
      }
      if(version.equals("1.21.11")){JsonObject item=new JsonObject();item.addProperty("version",version);item.addProperty("pack",pack.file.toString());gpu.add(item);}
    }
    int[] oldWidths=new int[NativeHudLayout.ALPHABET.length()];Arrays.fill(oldWidths,6);NativeHudLayout.Profile old=new NativeHudLayout.Profile("old","",NativeHudLayout.FIRST,0,8,oldWidths);
    check(NativeHudLayout.encode(old,"§lA").text.contains("§l"),"previously delivered packs retain their original metrics until the replacement is accepted");
    Files.write(project.resolve(".build/text-quality-proof.json"),gpu.toString().getBytes("UTF-8"));
    System.out.println("PASS: "+checks+" text quality checks; actual bold outlines, Cyrillic, sizes 4..32, clipping and restart.");
  }
}
