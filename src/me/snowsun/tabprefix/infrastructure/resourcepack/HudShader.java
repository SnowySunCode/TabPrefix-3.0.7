package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import me.snowsun.tabprefix.domain.*;

/** Versioned native shader interfaces; only signed private glyphs activate the HD path. */
public final class HudShader {
  private HudShader() {}
  public static boolean supported(MinecraftVersion v) {
    return v!=null&&v.supported()&&(v.major==1&&v.minor>=17||v.major==26);
  }
  public static boolean enabled(DisplayDesign d,MinecraftVersion v) {
    return d!=null&&d.hudQuality.equals("HD")&&supported(v);
  }
  public static String path(MinecraftVersion v) {
    return "assets/minecraft/shaders/core/"+(v.major==26&&v.minor>=2?"text":"rendertype_text");
  }
  static String family(MinecraftVersion v) {
    if(v.major==26)return "26"+v.minor;
    if(v.minor==17||v.minor==18&&v.patch==0)return "117";
    if(v.minor==18&&v.patch==1)return "1181";
    if(v.minor<20||v.minor==20&&v.patch<5)return "1182";
    if(v.minor==20||v.patch<2)return "1205";
    if(v.patch<6)return "1212";
    return v.patch<9?"1216":"1219";
  }
  /** Transparent GUI rows keep the metadata outside visible artwork. */
  public static void mark(BufferedImage image,int offset,boolean graphic) {
    mark(image,0,0,image.getWidth(),image.getHeight(),offset,graphic);
  }
  static void mark(BufferedImage image,int left,int top,int width,int height,int offset,boolean graphic) {
    markKind(image,left,top,width,height,offset,graphic?214:215);
  }
  static void markText(BufferedImage image,int left,int top,int width,int height,int offset) {
    markKind(image,left,top,width,height,offset,216);
  }
  static void markGraphicTile(BufferedImage image,int offset,int width,int height,int density,int edges) {
    if((density!=4&&density!=8)||image.getWidth()!=(width+2)*density||image.getHeight()!=Math.max(8,height+2)*density)
      throw new IllegalArgumentException("Invalid HD tile gutter");
    markKind(image,0,0,image.getWidth(),image.getHeight(),offset,217);
    for(int x:new int[]{0,image.getWidth()-1})for(int y:new int[]{0,image.getHeight()-1}) {
      int side=y==0?1:-1;
      image.setRGB(x,y+3*side,0x01000000|width<<16|height<<8|density);
      image.setRGB(x,y+4*side,0x01000000|edges<<16|0x0054db);
    }
  }
  private static void markKind(BufferedImage image,int left,int top,int width,int height,int offset,int kind) {
    if(offset< -1024||offset>1023||width<2||height<4)
      throw new IllegalArgumentException("Invalid HD glyph");
    int value=offset+1024,marker=0x01000000|value<<8|kind;
    for(int x:new int[]{left,left+width-1})for(int y:new int[]{top,top+height-1}) {
      image.setRGB(x,y,marker);
      int side=y==top?1:-1;
      image.setRGB(x,y+side,0x01545048);
      image.setRGB(x,y+2*side,0x01000000|((width&255)<<16)|((height&255)<<8)|(x==left?200:201));
    }
  }
  static String resource(String name)throws IOException {
    try(InputStream in=HudShader.class.getResourceAsStream("/shaders/hd/"+name)) {
      if(in==null)throw new IOException("Missing HD shader template: "+name);
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
      while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
      return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
  }
  public static void install(Map<String,byte[]> entries,MinecraftVersion v)throws IOException {
    if(!supported(v))throw new IOException("No core shader interface for Minecraft "+v);
    boolean locations=v.major==26&&v.minor>=3;
    String source=resource(family(v)+"/text.vsh");
    String declarations="uniform sampler2D Sampler0;\n"
        +(locations?"layout(location = 4) ":"")+"flat out int tabprefixKind;\n"
        +(locations?"layout(location = 5) ":"")+"flat out ivec4 tabprefixBounds;\n"
        +(locations?"layout(location = 6) ":"")+"flat out ivec4 tabprefixCore;\n";
    source=source.replace("void main() {",declarations+resource("marker.glsl")+"\nvoid main() {\n"
        +"    ivec2 marker=tabprefix_marker(tabprefixBounds,tabprefixCore);\n    tabprefixKind=marker.y;\n"
        +"    vec3 tabprefixPosition=Position;\n    if(marker.y!=0)tabprefixPosition.y+=float(marker.x);\n");
    source=source.replace("vec4(Position, 1.0);","vec4(tabprefixPosition, 1.0);\n"
        +"    if((marker.y==214||marker.y==217)&&min(Color.r,min(Color.g,Color.b))<0.9)gl_Position=vec4(3.0,3.0,3.0,1.0);");
    entries.put(path(v)+".vsh",source.getBytes(StandardCharsets.UTF_8));
    HudFragment.install(entries,v,resource(family(v)+"/text.fsh"),locations);
  }
}
