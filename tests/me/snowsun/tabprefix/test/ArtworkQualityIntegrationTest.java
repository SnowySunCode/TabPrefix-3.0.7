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

/** Reconstructs the delivered glyphs; checks borders and seams independently of the web canvas. */
public final class ArtworkQualityIntegrationTest {
  static int checks;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  static JsonObject paint(double width){JsonObject p=new JsonObject();p.addProperty("fill","#12212d");p.addProperty("end","#1e3841");p.addProperty("stroke","#71d9ca");p.addProperty("strokeWidth",width);p.addProperty("radius",8);p.addProperty("accent","none");p.addProperty("opacity",100);return p;}
  static JsonObject box(String surface,int width,int height,double stroke){JsonObject b=new JsonObject();b.addProperty("surface",surface);b.addProperty("index",0);b.addProperty("width",width);b.addProperty("height",height);b.add("paint",paint(stroke));return b;}
  static final class Glyph{BufferedImage image;int height,ascent,offset,padding,density=4,coreWidth,coreHeight;Glyph(BufferedImage i,int h,int a,int o,int p){image=i;height=h;ascent=a;offset=o;padding=p;}}
  static Map<Integer,Glyph> glyphs(ZipFile zip)throws Exception{
    Map<Integer,Glyph> result=new HashMap<>();JsonObject f;try(Reader in=new InputStreamReader(zip.getInputStream(zip.getEntry("assets/minecraft/font/default.json")),"UTF-8")){f=new JsonParser().parse(in).getAsJsonObject();}
    for(JsonElement e:f.getAsJsonArray("providers")){JsonObject p=e.getAsJsonObject();if(!p.has("file")||!p.get("file").getAsString().contains("hud-tile-"))continue;String name=p.get("file").getAsString().replace("tabprefix:","assets/tabprefix/textures/");BufferedImage image=ImageIO.read(zip.getInputStream(zip.getEntry(name)));check(image.getWidth()<=256&&image.getHeight()<=256,"HD tile fits the client atlas");
      int marker=image.getRGB(0,0),offset=(marker>>>24)==1&&((marker&255)==214||(marker&255)==217)?((marker>>>16)&255)*256+((marker>>>8)&255)-1024:0;
      int density=(marker&255)==217?image.getRGB(0,3)&255:4,coreWidth=(marker&255)==217?(image.getRGB(0,3)>>>16)&255:0,coreHeight=(marker&255)==217?(image.getRGB(0,3)>>>8)&255:0;
      if((marker&255)==214||(marker&255)==217)check(image.getHeight()==p.get("height").getAsInt()*density,"HD density remains constant at every Y offset");
      if((marker&255)==217)check((density==4||density==8)&&image.getWidth()==(coreWidth+2)*density,"HD tiles include two real neighbour gutters");
      for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)<=1)image.setRGB(x,y,0);
      Glyph glyph=new Glyph(image,p.get("height").getAsInt(),p.get("ascent").getAsInt(),offset,(marker>>>24)==1&&((marker&255)==214||(marker&255)==217)?density:0);
      glyph.density=density;glyph.coreWidth=coreWidth;glyph.coreHeight=coreHeight;
      result.put(p.getAsJsonArray("chars").get(0).getAsString().codePointAt(0),glyph);}
    return result;
  }
  static BufferedImage compose(NativeHudLayout.Profile profile,int frame,int width,int height,Map<Integer,Glyph> glyphs,Map<Integer,Integer> metrics){
    BufferedImage out=new BufferedImage(width*4,height*4,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();int cursor=0;String run=profile.glyph(frame);
    try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      for(int n=0;n<run.length();){int c=run.codePointAt(n);n+=Character.charCount(c);if(c=='§'){n++;continue;}if(c>=NativeHudLayout.SPACE&&c<NativeHudLayout.SPACE+26){int i=c-NativeHudLayout.SPACE;cursor+=(1<<(i%13))*(i>=13?-1:1);continue;}
        Glyph glyph=glyphs.get(c);check(glyph!=null,"frame references a delivered HD tile");int y=(7-glyph.ascent+glyph.offset-profile.y)*4,h=glyph.height*4,w=(int)Math.round(glyph.image.getWidth()*h/(double)glyph.image.getHeight());
        // Alpha-discard in the game leaves earlier tiles intact under the transparent marker rows.
        if(glyph.coreWidth>0)g.drawImage(glyph.image,(cursor+1)*4,y+4,(cursor+1+glyph.coreWidth)*4,y+4+glyph.coreHeight*4,glyph.density,glyph.density,(glyph.coreWidth+1)*glyph.density,(glyph.coreHeight+1)*glyph.density,null);
        else if(glyph.padding>0)g.drawImage(glyph.image,cursor*4,y+4,cursor*4+w,y+h-4,0,4,glyph.image.getWidth(),glyph.image.getHeight()-4,null);
        else g.drawImage(glyph.image,cursor*4,y,w,h,null);cursor+=metrics.get(c);}
    }finally{g.dispose();}check(cursor==profile.advance(frame),"composite cursor agrees with measured advance");return out;
  }
  static BufferedImage reference(DisplayDesign d,DisplayArtwork.Box box,int width,int height,int density){
    BufferedImage source=ArtworkRasterizer.render(d,box,width,height,density),out=new BufferedImage(width*4,height*4,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
    try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,out.getWidth(),out.getHeight(),null);}finally{g.dispose();}return out;
  }
  public static void main(String[] args)throws Exception{
    Path project=Paths.get(args[0]);JsonObject doc=DisplayDesign.defaults(false).json();doc.addProperty("sidebarEnabled",true);doc.addProperty("sidebarMode","HUD");doc.addProperty("sidebarWidth",232);doc.addProperty("headerEnabled",true);doc.addProperty("footerEnabled",true);doc.addProperty("layoutEnabled",true);JsonObject art=doc.getAsJsonObject("artwork");art.addProperty("panelHeight",196);art.add("panel",paint(.75));JsonArray boxes=new JsonArray();boxes.add(box("HEADER",280,26,.75));boxes.add(box("FOOTER",280,26,.5));boxes.add(box("CELL",120,8,.75));art.add("boxes",boxes);
    DisplayDesign d=new DisplayDesign(doc);check(d.artwork.panel.strokeWidth==.75f,"fractional thickness preserved");check(new DisplayDesign(d.json()).json().equals(d.json()),"fractional settings round trip");check(d.artwork.box("CELL",0).inset==.5f,"old cells acquire a small separating margin");
    for(double thickness:new double[]{.25,.5,.75,1,1.5,2,4,8,16})for(int width:new int[]{80,232,400}){
      JsonObject test=d.json();JsonObject panel=paint(thickness);panel.addProperty("fill","none");panel.addProperty("end","none");panel.addProperty("radius",0);test.getAsJsonObject("artwork").add("panel",panel);test.getAsJsonObject("artwork").addProperty("panelHeight",48);test.addProperty("sidebarWidth",width);BufferedImage image=ArtworkRasterizer.render(new DisplayDesign(test),null,width,48,4);
      double ink=0;for(int y=0;y<24*4;y++)ink+=(image.getRGB(width*2,y)>>>24)/255d;check(Math.abs(ink-thickness*4)<.05,"border thickness is independent of canvas width");for(int y=0;y<image.getHeight();y++)check(image.getRGB(0,y)==image.getRGB(image.getWidth()-1,y),"opposite borders are symmetric");
    }
    for(double thickness:new double[]{.25,.5,.75,1,1.5,2,4,8,16}){
      JsonObject lineDoc=d.json();lineDoc.addProperty("sidebarBackground",false);lineDoc.addProperty("sidebarWidth",96);lineDoc.getAsJsonObject("artwork").addProperty("panelHeight",64);
      JsonObject line=new JsonObject();line.addProperty("id","quality-line");line.addProperty("kind","LINE");line.addProperty("x",8);line.addProperty("y",8);line.addProperty("width",80);line.addProperty("height",40);JsonObject style=paint(thickness);style.addProperty("fill","none");line.add("paint",style);JsonArray shapes=new JsonArray();shapes.add(line);lineDoc.getAsJsonObject("artwork").add("shapes",shapes);
      BufferedImage image=ArtworkRasterizer.render(new DisplayDesign(lineDoc),null,96,64,4);double area=0;for(int x=0;x<image.getWidth();x++)for(int y=0;y<image.getHeight();y++)area+=(image.getRGB(x,y)>>>24)/255d/16;
      double expectedArea=Math.hypot(80,40)*thickness+Math.PI*thickness*thickness/4;check(Math.abs(area-expectedArea)<Math.max(1,expectedArea*.02),"diagonal stroke has its requested thickness and round caps");
      for(int x=8*4;x<88*4;x++){boolean ink=false;for(int y=0;y<image.getHeight();y++)ink|=(image.getRGB(x,y)>>>24)>0;check(ink,"thin diagonal has no missing pixel columns");}
      if(thickness==.75)ImageIO.write(image,"png",project.resolve(".build/artwork-quality-line.png").toFile());
    }
    Path root=Files.createTempDirectory(project.resolve(".build/test-data"),"quality-");PluginSettings cfg=HdVersionsIntegrationTest.settings(root,MinecraftVersion.parse("1.21.11"));PackBuilder builder=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://127.0.0.1:8999");builder.designs(()->d);PackRevision pack=builder.build(GraphicSnapshot.empty());NativeHudLayout hud=pack.hud;check(!hud.bitmapWidths.isEmpty(),"HD metrics delivered");check(builder.load(GraphicSnapshot.empty()).hud.json().equals(hud.json()),"composite frames survive pack reload");
    try(ZipFile zip=new ZipFile(pack.file.toFile())){Map<Integer,Integer> widths=ArtworkIntegrationTest.metrics(zip);for(Map.Entry<Integer,Integer> e:hud.bitmapWidths.entrySet())check(e.getValue().equals(widths.get(e.getKey())),"tile advance measured independently from PNG");Map<Integer,Glyph> glyphs=glyphs(zip);
      NativeHudLayout.Profile header=hud.profiles.get("tab:header:0");long repeated=header.glyph(0).codePoints().filter(hud.bitmapWidths::containsKey).count(),unique=header.glyph(0).codePoints().filter(hud.bitmapWidths::containsKey).distinct().count();check(unique<repeated,"identical HD fragments reuse one delivered glyph");BufferedImage actual=compose(header,0,280,26,glyphs,widths),expected=reference(d,d.artwork.box("HEADER",0),280,26,glyphs.values().iterator().next().density);int differences=0;for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++)if((actual.getRGB(x,y)>>>24)>1||(expected.getRGB(x,y)>>>24)>1)if(actual.getRGB(x,y)!=expected.getRGB(x,y))differences++;check(differences==0,"HD header reconstructs exactly across tile seams");ImageIO.write(actual,"png",project.resolve(".build/artwork-quality-header.png").toFile());
      NativeHudLayout.Profile sidebar=hud.profiles.get("sidebar:background");BufferedImage panel=compose(sidebar,0,232,196,glyphs,widths);for(int y=8*4;y<(196-8)*4;y++)check((panel.getRGB(0,y)>>>24)>100,"thin vertical frame has no gaps at band boundaries");ImageIO.write(panel,"png",project.resolve(".build/artwork-quality-sidebar.png").toFile());
      NativeHudLayout.Profile cell=hud.profiles.get("tab:cell:0:compact");int target=TabMetrics.compactBoxWidths(d,d.artwork.box("CELL",0))[0]-1;BufferedImage card=compose(cell,0,target,9,glyphs,widths);check((card.getRGB(0,0)>>>24)==0,"cell has clear rounded outer margin");ImageIO.write(card,"png",project.resolve(".build/artwork-quality-cell.png").toFile());
    }
    System.out.println("PASS: "+checks+" artwork quality checks: fractional strokes, symmetric borders, independent HD PNG metrics, seamless reconstruction, cell insets and metadata restart.");
  }
}
