package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;

/** Exercises actual pack generation without a server. Config parsing has its own Bukkit tests. */
public final class HdVersionsIntegrationTest {
  static int checks;
  static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000305");
  static void check(boolean v,String why){if(!v)throw new AssertionError(why);checks++;}
  static void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
  static PluginSettings settings(Path root,MinecraftVersion version)throws Exception {
    // Immutable configuration fixture: no Bukkit implementation or game client is emulated.
    Class<?> type=Class.forName("sun.misc.Unsafe");Field f=type.getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);
    PluginSettings cfg=(PluginSettings)type.getMethod("allocateInstance",Class.class).invoke(unsafe,PluginSettings.class);
    FeatureSettings features=new FeatureSettings(new FeatureSettings.Source(){
      public Object get(String key,Object fallback){return key.equals("images.allowed-formats")?Arrays.asList("png","jpg","gif"):fallback;}
      public Set<String> keys(String path){return Collections.emptySet();}
    });
    field(cfg,"features",features);field(cfg,"minecraft",version);
    for(String name:new String[]{"dataDirectory","tempDirectory","packsDirectory","stagingDirectory"}){Path p=root.resolve(name);Files.createDirectories(p);field(cfg,name,p);}
    return cfg;
  }
  static GraphicSnapshot assets(PluginSettings cfg)throws Exception {
    Path dir=cfg.dataDirectory.resolve("assets").resolve(ID.toString());Files.createDirectories(dir);
    for(int frame=0;frame<2;frame++){
      BufferedImage image=new BufferedImage(256,64,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
      try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(frame==0?0x7cdcd1:0xe498d1));g.setStroke(new BasicStroke(3));g.drawLine(1,1,frame==0?254:120,62);g.fillOval(20,10,40,40);}finally{g.dispose();}
      ImageIO.write(image,"png",dir.resolve(String.format("frame-%04d.png",frame)).toFile());
    }
    AssetDescriptor a=new AssetDescriptor(ID,"gif",256,64,16,13,new int[]{2,3},new int[]{0xe000,0xe001},1);
    return new GraphicSnapshot(Collections.singletonMap(ID,a),Collections.emptyMap(),Collections.emptyMap());
  }
  static JsonObject document(){
    JsonObject d=DisplayDesign.defaults(false).json();d.addProperty("layoutEnabled",true);d.addProperty("headerEnabled",true);d.addProperty("footerEnabled",true);d.addProperty("sidebarEnabled",true);d.addProperty("sidebarMode","HUD");d.addProperty("sidebarY",-180);d.addProperty("sidebarWidth",232);d.addProperty("tabWidth",280);d.addProperty("screenEnabled",true);
    JsonArray screens=new JsonArray();for(String kind:new String[]{"TEXT","IMAGE"}){JsonObject s=new JsonObject();s.addProperty("id",kind.equals("TEXT")?"descenders":"media");s.addProperty("kind",kind);s.addProperty("anchor","FREE_XY");s.addProperty("height",kind.equals("TEXT")?32:16);s.addProperty("y",kind.equals("TEXT")?-220:-300);if(kind.equals("IMAGE"))s.addProperty("image",ID.toString());JsonObject line=new JsonObject();line.addProperty("text","j Друg");s.add("line",line);screens.add(s);}d.add("screen",screens);
    d.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("kind","IMAGE");d.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("image",ID.toString());
    JsonArray boxes=new JsonArray();for(String surface:new String[]{"CELL","HEADER","FOOTER"}){JsonObject b=new JsonObject();b.addProperty("surface",surface);b.addProperty("index",0);b.addProperty("width",surface.equals("CELL")?120:280);b.addProperty("height",surface.equals("CELL")?8:26);b.addProperty("align","LEFT");JsonObject paint=new JsonObject();paint.addProperty("fill","#142330");paint.addProperty("end","#142330");paint.addProperty("stroke","#7cdcd1");paint.addProperty("strokeWidth",.5);paint.addProperty("radius",3);paint.addProperty("accent","none");paint.addProperty("opacity",100);b.add("paint",paint);boxes.add(b);}d.getAsJsonObject("artwork").add("boxes",boxes);return d;
  }
  static JsonObject json(ZipFile z,String name)throws Exception{try(Reader r=new InputStreamReader(z.getInputStream(z.getEntry(name)),StandardCharsets.UTF_8)){return new JsonParser().parse(r).getAsJsonObject();}}
  static Map<Integer,Integer> metrics(ZipFile z)throws Exception {
    Map<Integer,Integer> result=new HashMap<>();Map<String,BufferedImage> images=new LinkedHashMap<String,BufferedImage>(8,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,BufferedImage> e){return size()>4;}};
    for(JsonElement e:json(z,"assets/minecraft/font/default.json").getAsJsonArray("providers")){
      JsonObject p=e.getAsJsonObject();if(p.get("type").getAsString().equals("space")){for(Map.Entry<String,JsonElement> item:p.getAsJsonObject("advances").entrySet())result.put(item.getKey().codePointAt(0),item.getValue().getAsInt());continue;}
      String name=p.get("file").getAsString().replace("tabprefix:","assets/tabprefix/textures/");BufferedImage image=images.get(name);if(image==null){image=ImageIO.read(z.getInputStream(z.getEntry(name)));images.put(name,image);}
      JsonArray rows=p.getAsJsonArray("chars");int cols=rows.get(0).getAsString().codePointCount(0,rows.get(0).getAsString().length()),cw=image.getWidth()/cols,ch=image.getHeight()/rows.size();check(cw<=256&&ch<=256,"glyph fits native font atlas");check(p.get("ascent").getAsInt()<=p.get("height").getAsInt(),"legal provider ascent");
      for(int y=0;y<rows.size();y++){int[] codes=rows.get(y).getAsString().codePoints().toArray();for(int x=0;x<cols;x++)if(codes[x]!=0){int ink=0;outer:for(int c=cw-1;c>=0;c--)for(int r=0;r<ch;r++)if((image.getRGB(x*cw+c,y*ch+r)>>>24)!=0){ink=c+1;break outer;}result.put(codes[x],(int)(.5f+ink*p.get("height").getAsFloat()/ch)+1);}}
    }return result;
  }
  static void legacy(PluginSettings cfg,GraphicSnapshot assets)throws Exception {
    Path fonts=cfg.dataDirectory.resolve("legacy-font");Files.createDirectories(fonts);byte[] original=new byte[65536];original[65]=0x17;original[0x410]=0x27;original[0xe010]=0x0f;Files.write(fonts.resolve("glyph_sizes.bin"),original);
    BufferedImage old=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);old.setRGB(0,16,0xff9933cc);ImageIO.write(old,"png",fonts.resolve("unicode_page_e0.png").toFile());
    PackBuilder b=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://localhost");PackRevision p=b.build(assets);
    try(ZipFile z=new ZipFile(p.file.toFile())){BufferedImage page=ImageIO.read(z.getInputStream(z.getEntry("assets/minecraft/textures/font/unicode_page_e0.png")));check(page.getWidth()==1024&&page.getHeight()==1024,"legacy textures keep fourfold density");check(page.getRGB(0,64)==0xff9933cc,"unassigned Unicode glyph is preserved");byte[] bytes=new byte[65536];try(DataInputStream in=new DataInputStream(z.getInputStream(z.getEntry("assets/minecraft/font/glyph_sizes.bin")))){in.readFully(bytes);}check(bytes[65]==original[65]&&bytes[0x410]==original[0x410]&&bytes[0xe010]==original[0xe010],"ordinary Latin/Cyrillic/private glyph metrics unchanged");check(json(z,"tabprefix-glyphs.json").get("density").getAsInt()==4,"legacy density recorded");}
    check(b.load(assets).hash.equals(p.hash),"legacy HD pack survives reload");
  }
  public static void main(String[] args)throws Exception {
    Path project=Paths.get(args[0]);Files.createDirectories(project.resolve(".build/test-data"));JsonArray proof=new JsonArray();DisplayDesign d=new DisplayDesign(document());
    for(int minor=12;minor<=21;minor++)for(int patch=0;patch<=11;patch++){MinecraftVersion v=MinecraftVersion.parse("1."+minor+"."+patch);check(HudShader.supported(v)==(v.supported()&&minor>=17),"HD gate accepts only the known release range");}
    for(String version:new String[]{"1.22","26.4","27.1"})check(!HudShader.supported(MinecraftVersion.parse(version)),"future shader interfaces are not guessed");
    for(String version:new String[]{"1.17","1.18.1","1.18.2","1.20.5","1.21.2","1.21.6","1.21.11","26.1","26.2","26.3"}){
      PluginSettings cfg=settings(Files.createTempDirectory(project.resolve(".build/test-data"),"hd-"),MinecraftVersion.parse(version));GraphicSnapshot assets=assets(cfg);PackBuilder b=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://localhost");b.designs(()->d);PackRevision pack=b.build(assets);
      check(b.load(assets).hash.equals(pack.hash),"HD pack and metadata survive restart: "+version);check(pack.hud.matches(d),"authored media and layouts match published pack");
      try(ZipFile z=new ZipFile(pack.file.toFile())){
        check(z.getEntry(HudShader.path(cfg.minecraft)+".vsh")!=null&&z.getEntry(HudShader.path(cfg.minecraft)+".fsh")!=null,"correct shader name for "+version);
        Map<Integer,Integer> metrics=metrics(z);for(NativeHudLayout.Profile p:pack.hud.profiles.values())for(int n=0;n<p.frames();n++){check(metrics.get(p.base+n)==p.advance(n),"canonical glyph advance matches real texture");if(p.hasBold())check(metrics.get(p.boldBase+n)==p.advance(n,true),"canonical bold advance matches real texture");}
        for(Map.Entry<Integer,Integer> item:pack.hud.bitmapWidths.entrySet())check(item.getValue().equals(metrics.get(item.getKey())),"HD tile advance matches real texture");
        for(String id:new String[]{"sidebar:background","tab:cell:0:row","tab:header:0","tab:footer:0","screen:media"}){NativeHudLayout.Profile p=pack.hud.profiles.get(id);check(p!=null&&p.glyph(0).codePoints().anyMatch(pack.hud.bitmapWidths::containsKey),"HD composite is delivered for "+id);}
        NativeHudLayout.Profile photo=pack.hud.profiles.get("tab:image:"+ID);check(photo.frames()==2&&photo.advance(0)==photo.advance(1),"GIF reserves stable width across frames");check(!photo.glyph(0).equals(photo.glyph(1)),"GIF frames retain independent artwork");
        for(int frame=0;frame<photo.frames();frame++)check(photo.glyph(frame).codePointCount(0,photo.glyph(frame).length())==1&&photo.glyph(frame).codePointAt(0)==photo.base+frame,"compact HD icon stays atomic and readable by preview");
        NativeHudLayout.Profile screen=pack.hud.profiles.get("screen:media");check(screen.advance(0)==screen.advance(1),"screen GIF keeps canvas width when frame ink changes");
      }
      JsonObject old=pack.hud.json();old.addProperty("rendererVersion",6);check(!NativeHudLayout.read(old).matches(d),"old renderer automatically requires a new pack");
      JsonObject item=new JsonObject();item.addProperty("version",version);item.addProperty("pack",pack.file.toString());proof.add(item);
    }
    for(String version:new String[]{"1.13.2","1.16.5","1.17","26.2","26.3"}){
      PluginSettings cfg=settings(Files.createTempDirectory(project.resolve(".build/test-data"),"compatible-"),MinecraftVersion.parse(version));JsonObject doc=d.json();doc.addProperty("hudQuality","COMPATIBLE");DisplayDesign c=new DisplayDesign(doc);PackBuilder b=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://localhost");b.designs(()->c);GraphicSnapshot assets=assets(cfg);PackRevision p=b.build(assets);try(ZipFile z=new ZipFile(p.file.toFile())){check(z.getEntry(HudShader.path(cfg.minecraft)+".vsh")==null,"compatibility path needs no shader");check(!p.hud.bitmapWidths.isEmpty(),"legal artwork fallback remains available");}check(b.load(assets).hash.equals(p.hash),"compatible pack survives reload");
    }
    PluginSettings cfg=settings(Files.createTempDirectory(project.resolve(".build/test-data"),"legacy-hd-"),MinecraftVersion.parse("1.12.2"));legacy(cfg,assets(cfg));
    Files.write(project.resolve(".build/hud-gpu-proof.json"),proof.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("PASS: "+checks+" HD version checks; ten real pack variants, old clients, PNG/GIF geometry, persistent metadata and shader selection.");
  }
}
