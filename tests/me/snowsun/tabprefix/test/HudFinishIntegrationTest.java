package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;

/** Checks delivered pixels and the actual native row formula, including offline-mode clients. */
public final class HudFinishIntegrationTest {
  static int checks;
  static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
  static JsonObject document(){
    JsonObject doc=DisplayDesign.defaults(false).json();doc.addProperty("layoutEnabled",true);doc.addProperty("sidebarEnabled",true);doc.addProperty("sidebarMode","HUD");doc.addProperty("sidebarY",-180);doc.addProperty("sidebarWidth",232);doc.addProperty("tabWidth",280);
    doc.addProperty("screenEnabled",true);JsonObject screen=new JsonObject();screen.addProperty("id","descenders");screen.addProperty("kind","TEXT");screen.addProperty("anchor","FREE_XY");screen.addProperty("height",32);screen.addProperty("y",-220);JsonObject text=new JsonObject();text.addProperty("text","j Друg");screen.add("line",text);JsonArray screens=new JsonArray();screens.add(screen);doc.add("screen",screens);
    JsonArray boxes=new JsonArray();JsonObject box=new JsonObject();box.addProperty("surface","CELL");box.addProperty("index",0);box.addProperty("width",120);box.addProperty("height",8);box.addProperty("align","LEFT");JsonObject paint=new JsonObject();paint.addProperty("fill","#142330");paint.addProperty("end","#142330");paint.addProperty("stroke","#7cdcd1");paint.addProperty("strokeWidth",.5);paint.addProperty("radius",3);paint.addProperty("accent","none");paint.addProperty("opacity",100);box.add("paint",paint);boxes.add(box);doc.getAsJsonObject("artwork").add("boxes",boxes);return doc;
  }
  static PackRevision pack(Path project,String version,DisplayDesign d)throws Exception {
    Path root=Files.createTempDirectory(project.resolve(".build/test-data"),"finish-");PluginSettings cfg=HdVersionsIntegrationTest.settings(root,MinecraftVersion.parse(version));PackBuilder b=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://127.0.0.1:8999");b.designs(()->d);PackRevision p=b.build(GraphicSnapshot.empty());check(b.load(GraphicSnapshot.empty()).hud.json().equals(p.hud.json()),"finished pack survives restart");return p;
  }
  static void descender(ZipFile zip,NativeHudLayout hud)throws Exception {
    NativeHudLayout.Profile profile=hud.get("screen:descenders",-220,32,"");check(profile!=null,"maximum-size text profile exists");int target=profile.base+NativeHudLayout.ALPHABET.indexOf('j');long actual=-1;
    JsonObject font;try(Reader in=new InputStreamReader(zip.getInputStream(zip.getEntry("assets/minecraft/font/default.json")),"UTF-8")){font=new JsonParser().parse(in).getAsJsonObject();}
    for(JsonElement e:font.getAsJsonArray("providers")){JsonObject p=e.getAsJsonObject();if(!p.has("file"))continue;JsonArray rows=p.getAsJsonArray("chars");for(int y=0;y<rows.size();y++){int[] codes=rows.get(y).getAsString().codePoints().toArray();for(int x=0;x<codes.length;x++)if(codes[x]==target){String path=p.get("file").getAsString().replace("tabprefix:","assets/tabprefix/textures/");BufferedImage image=ImageIO.read(zip.getInputStream(zip.getEntry(path)));int cw=image.getWidth()/codes.length,ch=image.getHeight()/rows.size();actual=0;for(int py=0;py<ch;py++)for(int px=0;px<cw;px++){int pixel=image.getRGB(x*cw+px,y*ch+py);int a=(pixel>>>24)<=1?0:((pixel>>>8)&255);if(a>1)actual+=a;}}}}
    java.awt.Font source;try(InputStream in=HudFinishIntegrationTest.class.getResourceAsStream("/fonts/DejaVuSans.ttf")){source=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,in).deriveFont(128f);}
    BufferedImage reference=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);java.awt.Graphics2D g=reference.createGraphics();try{g.setFont(source);g.setColor(java.awt.Color.WHITE);g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.drawGlyphVector(source.createGlyphVector(g.getFontRenderContext(),"j"),64,128);}finally{g.dispose();}
    long expected=0;for(int y=0;y<256;y++)for(int x=0;x<256;x++){int a=reference.getRGB(x,y)>>>24;if(a>1)expected+=a;}
    check(actual==expected,"negative left bearing and descender retain the complete glyph ink: "+actual+" expected "+expected);
  }
  public static void main(String[] args)throws Exception {
    Path project=Paths.get(args[0]);JsonArray gpu=new JsonArray();DisplayDesign d=new DisplayDesign(document());check(d.hudQuality.equals("HD")&&d.tabHeads.equals("AUTO"),"old documents receive safe defaults");check(d.artwork.box("CELL",0).fit.equals("ROW"),"old custom cells stretch by default");
    for(String version:new String[]{"1.17","1.18.1","1.18.2","1.20.5","1.21.2","1.21.6","1.21.11","26.1","26.2","26.3"}){
      PackRevision p=pack(project,version,d);try(ZipFile zip=new ZipFile(p.file.toFile())){
        check(zip.getEntry(HudShader.path(MinecraftVersion.parse(version))+".vsh")!=null,"version-specific HD vertex patch installed");
        check(zip.getEntry(HudShader.path(MinecraftVersion.parse(version))+".fsh")!=null,"scoped area filter installed with the vertex shader");
        descender(zip,p.hud);
        Map<Integer,Integer> metrics=ArtworkIntegrationTest.metrics(zip);Map<Integer,ArtworkQualityIntegrationTest.Glyph> glyphs=ArtworkQualityIntegrationTest.glyphs(zip);
        NativeHudLayout.Profile row=p.hud.profiles.get("tab:cell:0:row");check(row.frames()==8,"all native column / head geometries delivered");
        for(int cols=1;cols<=4;cols++)for(boolean heads:new boolean[]{false,true}){
          int limit=TabMetrics.contentWidth(d.tabWidth,cols,d.tabColumnGap),frame=(cols-1)*2+(heads?1:0),nativeWidth=limit+13+(heads?9:0);
          String text=ArtworkText.cell(p.hud,d,0,"§a[HELPER] §fAle9andrO",limit,cols,heads,0,false);
          check(ArtworkIntegrationTest.width(text,metrics)==limit,"row artwork never inflates the measured name width");
          BufferedImage actual=ArtworkQualityIntegrationTest.compose(row,frame,nativeWidth,8,glyphs,metrics);
          check((actual.getRGB((nativeWidth-1)*4,4*4)>>>24)>200,"frame reaches the real native row right edge after ping");
          check((actual.getRGB((nativeWidth-10)*4,4*4)>>>24)>200,"native ping area lies inside the frame");
          if(heads)for(int y=0;y<8*4;y++)for(int x=0;x<8*4;x++)check((actual.getRGB(x,y)>>>24)<=1,"font overlay preserves the already drawn native face");
          else check((actual.getRGB(0,4*4)>>>24)>200,"offline row has no phantom head offset");
          check(nativeWidth*cols+5*(cols-1)<=d.tabWidth,"complete native table stays within configured width");
        }
        JsonObject proof=new JsonObject();proof.addProperty("version",version);proof.addProperty("pack",p.file.toString());gpu.add(proof);
      }
      JsonObject compatible=d.json();compatible.addProperty("hudQuality","COMPATIBLE");check(!p.hud.matches(new DisplayDesign(compatible)),"quality change requires a new pack");
    }
    Files.write(project.resolve(".build/hud-gpu-proof.json"),gpu.toString().getBytes(StandardCharsets.UTF_8));
    for(int y:new int[]{-512,-180,-110,120}) {
      JsonObject doc=d.json();doc.addProperty("sidebarY",y);PackRevision p=pack(project,"1.21.11",new DisplayDesign(doc));try(ZipFile zip=new ZipFile(p.file.toFile())){
        Map<Integer,ArtworkQualityIntegrationTest.Glyph> glyphs=ArtworkQualityIntegrationTest.glyphs(zip);for(ArtworkQualityIntegrationTest.Glyph g:glyphs.values())check(g.image.getHeight()==g.height*g.density,"density is independent of vertical position");
        NativeHudLayout.Profile panel=p.hud.profiles.get("sidebar:background");BufferedImage actual=ArtworkQualityIntegrationTest.compose(panel,0,232,d.artwork.height(d),glyphs,ArtworkIntegrationTest.metrics(zip));BufferedImage expected=ArtworkQualityIntegrationTest.reference(d,null,232,d.artwork.height(d),glyphs.values().iterator().next().density);
        for(int py=0;py<actual.getHeight();py++)for(int x=0;x<actual.getWidth();x++)if((expected.getRGB(x,py)>>>24)>1)check(actual.getRGB(x,py)==expected.getRGB(x,py),"sidebar pixels are exact at every Y, including -512");
      }
    }
    JsonObject compat=d.json();compat.addProperty("hudQuality","COMPATIBLE");for(String version:new String[]{"1.21.11","1.16.5","26.3"}){
      PackRevision p=pack(project,version,new DisplayDesign(compat));try(ZipFile zip=new ZipFile(p.file.toFile())){check(zip.getEntry(HudShader.path(MinecraftVersion.parse(version))+".vsh")==null,"compatibility retains the bitmap path");check(p.hud.profiles.containsKey("tab:cell:0:row"),"full native row geometry also works without a shader");}
    }
    Files.write(project.resolve(".build/hud-gpu-proof.json"),gpu.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("PASS: "+checks+" HUD finish checks: full native rows, heads/ping, no width feedback, constant density, extreme Y, exact pixels, quality switching and restart.");
  }
}
