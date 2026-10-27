package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;

/** Independent circle integration and opacity invariants, plus a real pack for fractional GPU seams. */
public final class ContourQualityIntegrationTest {
  static int checks;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  static JsonObject doc(int radius,double stroke,int opacity){
    JsonObject doc=DisplayDesign.defaults(false).json();doc.addProperty("sidebarEnabled",true);doc.addProperty("sidebarBackground",true);doc.addProperty("sidebarMode","HUD");
    JsonObject p=doc.getAsJsonObject("artwork").getAsJsonObject("panel");
    p.addProperty("fill","#ffffff");p.addProperty("end","#ffffff");p.addProperty("stroke","#ffffff");p.addProperty("accent","none");p.addProperty("radius",radius);p.addProperty("strokeWidth",stroke);p.addProperty("opacity",opacity);return doc;
  }
  // Midpoint integration of vertical circle slices; independent of the production antiderivative.
  static double circlePixel(int x,int y,double radius){
    int n=2048;double area=0;
    for(int i=0;i<n;i++){
      double dx=x+(i+.5)/n-radius,edge=Math.sqrt(Math.max(0,radius*radius-dx*dx));
      area+=Math.max(0,Math.min(y+1d,radius+edge)-Math.max(y,radius-edge))/n;
    }
    return area;
  }
  public static void main(String[] args)throws Exception {
    Path root=Paths.get(args[0]);
    for(int radius:new int[]{3,9,31})for(int density:new int[]{1,2,4,8}) {
      int size=radius*2;BufferedImage image=ArtworkRasterizer.render(new DisplayDesign(doc(radius,0,100)),null,size,size,density);double area=0;
      for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
        int alpha=image.getRGB(x,y)>>>24;area+=alpha/255d/density/density;
        check(alpha==(image.getRGB(image.getWidth()-1-x,y)>>>24),"circular corners mirror horizontally");
        check(alpha==(image.getRGB(x,image.getHeight()-1-y)>>>24),"circular corners mirror vertically");
        if(alpha>0&&alpha<255){double expected=circlePixel(x,y,radius*density)*255;check(Math.abs(alpha-expected)<=1.01,"circular edge matches independent subpixel integration: "+alpha+" / "+expected);}
      }
      check(Math.abs(area-Math.PI*radius*radius)<.08,"circle area is independent of density and radius: "+area);
    }
    for(int density:new int[]{1,4,8})for(int opacity:new int[]{33,75,100}) {
      BufferedImage reference=ArtworkRasterizer.render(new DisplayDesign(doc(13,0,opacity)),null,80,48,density);
      for(double stroke:new double[]{.25,.5,.75,1,1.5,3.5,8,16}) {
        BufferedImage image=ArtworkRasterizer.render(new DisplayDesign(doc(13,stroke,opacity)),null,80,48,density);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
          check((image.getRGB(x,y)>>>24)==(reference.getRGB(x,y)>>>24),"fill/outline share a single coverage and opacity, including rounded joins");
      }
    }
    for(int density:new int[]{1,4,8})for(int opacity:new int[]{33,75,100}) {
      JsonObject accented=doc(13,.75,opacity);JsonObject paint=accented.getAsJsonObject("artwork").getAsJsonObject("panel");paint.addProperty("accent","#83e9d8");paint.addProperty("accentWidth",2);
      BufferedImage actual=ArtworkRasterizer.render(new DisplayDesign(accented),null,80,48,density),reference=ArtworkRasterizer.render(new DisplayDesign(doc(13,.75,opacity)),null,80,48,density);
      for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++)check((actual.getRGB(x,y)>>>24)==(reference.getRGB(x,y)>>>24),"accent uses the same object opacity without a darker overlap");
      JsonObject ellipseDoc=doc(0,1.5,opacity);ellipseDoc.addProperty("sidebarBackground",false);JsonObject ellipse=new JsonObject();ellipse.addProperty("id","opacity-ellipse");ellipse.addProperty("kind","ELLIPSE");ellipse.addProperty("x",8);ellipse.addProperty("y",8);ellipse.addProperty("width",40);ellipse.addProperty("height",24);ellipse.add("paint",ellipseDoc.getAsJsonObject("artwork").getAsJsonObject("panel"));JsonArray shapes=new JsonArray();shapes.add(ellipse);ellipseDoc.getAsJsonObject("artwork").add("shapes",shapes);
      actual=ArtworkRasterizer.render(new DisplayDesign(ellipseDoc),null,80,48,density);
      for(int x=8*density;x<48*density;x++)check((actual.getRGB(x,20*density)>>>24)==Math.round(opacity*2.55),"filled ellipse and its outline have one object opacity");
    }
    JsonObject document=doc(13,.75,75);document.addProperty("sidebarWidth",136);document.addProperty("sidebarY",0);document.getAsJsonObject("artwork").addProperty("panelHeight",72);
    JsonObject p=document.getAsJsonObject("artwork").getAsJsonObject("panel");p.addProperty("fill","#123147");p.addProperty("end","#284b65");p.addProperty("stroke","#83e9d8");
    DisplayDesign d=new DisplayDesign(document);Path data=Files.createTempDirectory(root.resolve(".build/test-data"),"contours-");PluginSettings cfg=HdVersionsIntegrationTest.settings(data,MinecraftVersion.parse("1.21.11"));
    PackBuilder builder=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://127.0.0.1:8999");builder.designs(()->d);PackRevision pack=builder.build(GraphicSnapshot.empty());
    ImageIO.write(ArtworkRasterizer.render(d,null,136,72,8),"png",root.resolve(".build/contour-reference.png").toFile());
    JsonObject proof=new JsonObject();proof.addProperty("version","1.21.11");proof.addProperty("pack",pack.file.toString());proof.addProperty("reference",root.resolve(".build/contour-reference.png").toString());
    Files.write(root.resolve(".build/contour-quality-proof.json"),proof.toString().getBytes(StandardCharsets.UTF_8));
    JsonObject old=pack.hud.json();old.addProperty("rendererVersion",8);check(!NativeHudLayout.read(old).matches(d),"renderer 8 must trigger a pack rebuild");
    System.out.println("PASS: "+checks+" contour checks: analytic circle area, independent edge integration, symmetry, no fill/outline opacity seam, renderer migration.");
  }
}
