package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.domain.NativeHudLayout;

/** Variable-width, supersampled text with real font weights and isolated glyph cells. */
final class HudTextAtlas {
  private HudTextAtlas() {}
  private static final Map<String,Baked> CACHE=new LinkedHashMap<String,Baked>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Baked> e){return size()>64;}};
  private static final Map<String,Letters> LETTERS=new LinkedHashMap<String,Letters>(4,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Letters> e){return size()>4;}};
  private static final class Letters {final int[] advances;final java.util.List<BufferedImage> images;final Map<Integer,java.util.List<Integer>> groups;Letters(int[] a,java.util.List<BufferedImage> i,Map<Integer,java.util.List<Integer>> g){advances=a;images=i;groups=g;}}
  private static final class Baked {final int[] advances;final Map<Integer,byte[]> pngs;final Map<Integer,java.util.List<Integer>> groups;final int height;Baked(int[] a,Map<Integer,byte[]> p,Map<Integer,java.util.List<Integer>> g,int h){advances=a;pngs=p;groups=g;height=h;}}
  static int[] generate(Map<String,byte[]> entries,JsonArray providers,Font font,int base,int y,int size)throws IOException {
    String key=font.getFontName()+":"+font.getStyle()+":"+size+":"+y;Baked baked;synchronized(CACHE){baked=CACHE.get(key);}
    if(baked==null){baked=bake(font,y,size);synchronized(CACHE){CACHE.put(key,baked);}}
    for(Map.Entry<Integer,java.util.List<Integer>> group:baked.groups.entrySet()) {
      int width=group.getKey(),cols=Math.min(16,group.getValue().size()),rows=(group.getValue().size()+cols-1)/cols;JsonArray chars=new JsonArray();
      for(int row=0;row<rows;row++){StringBuilder text=new StringBuilder();for(int col=0;col<cols;col++){int index=row*cols+col;if(index>=group.getValue().size())text.append('\0');else text.appendCodePoint(base+group.getValue().get(index));}chars.add(text.toString());}
      String file="hud-hd-text-"+size+"-"+y+"-"+font.getStyle()+"-"+width+".png";entries.put("assets/tabprefix/textures/font/"+file,baked.pngs.get(width));
      JsonObject p=new JsonObject();p.addProperty("type","bitmap");p.addProperty("file","tabprefix:font/"+file);p.addProperty("height",baked.height);p.addProperty("ascent",7);p.add("chars",chars);providers.add(p);
    }
    return baked.advances;
  }
  static int density(int size) { return 4; }
  private static Letters letters(Font font,int size) {
    int density=density(size),bodyHeight=Math.max(12,size+8);
    Map<Integer,java.util.List<Integer>> groups=new TreeMap<>();
    java.util.List<BufferedImage> letters=new ArrayList<>();int[] advances=new int[NativeHudLayout.ALPHABET.length()];
    for(int n=0;n<advances.length;n++) {
      BufferedImage image=new BufferedImage((size+8)*density,bodyHeight*density,BufferedImage.TYPE_INT_ARGB);
      Graphics2D g=image.createGraphics();try {
        g.setFont(font.deriveFont((float)size*density));g.setColor(Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int baseline=g.getFontMetrics().getAscent();java.awt.font.GlyphVector glyph=g.getFont().createGlyphVector(g.getFontRenderContext(),new char[]{NativeHudLayout.ALPHABET.charAt(n)});
        Rectangle bounds=glyph.getPixelBounds(g.getFontRenderContext(),0,baseline);
        // Antialiasing can shade one pixel beyond GlyphVector's reported bounds.
        g.drawGlyphVector(glyph,Math.max(0,1-bounds.x),baseline+Math.max(0,1-bounds.y));
      }finally{g.dispose();}
      int right=0;for(int x=0;x<image.getWidth();x++)for(int py=0;py<image.getHeight();py++)if((image.getRGB(x,py)>>>24)>0)right=Math.max(right,x+1);
      int width=Math.max(2,(right+density)/density);advances[n]=width+1;
      BufferedImage cropped=new BufferedImage(width*density,(bodyHeight+2)*density,BufferedImage.TYPE_INT_ARGB);Graphics2D cg=cropped.createGraphics();try{cg.setComposite(AlphaComposite.Src);cg.drawImage(image,0,density,null);}finally{cg.dispose();}
      // Distance and coverage depend on the font, never on the row's screen position.
      HudTextDistance.encode(cropped);letters.add(cropped);
      groups.computeIfAbsent(width,k->new ArrayList<>()).add(n);
    }
    return new Letters(advances,letters,groups);
  }
  private static Baked bake(Font font,int y,int size)throws IOException {
    int density=density(size),height=Math.max(12,size+8)+2;String key=font.getFontName()+":"+font.getStyle()+":"+size;Letters source;synchronized(LETTERS){source=LETTERS.get(key);}
    if(source==null){source=letters(font,size);synchronized(LETTERS){LETTERS.put(key,source);}}
    Map<Integer,java.util.List<Integer>> groups=source.groups;
    Map<Integer,byte[]> pngs=new TreeMap<>();for(Map.Entry<Integer,java.util.List<Integer>> group:groups.entrySet()) {
      int width=group.getKey(),cols=Math.min(16,group.getValue().size()),rows=(group.getValue().size()+cols-1)/cols;
      BufferedImage atlas=new BufferedImage(cols*width*density,rows*height*density,BufferedImage.TYPE_INT_ARGB);Graphics2D g=atlas.createGraphics();g.setComposite(AlphaComposite.Src);
      try {for(int row=0;row<rows;row++)for(int col=0;col<cols;col++){
        int index=row*cols+col;if(index>=group.getValue().size())continue;
        int letter=group.getValue().get(index),left=col*width*density,top=row*height*density;
        g.drawImage(source.images.get(letter),left,top,null);HudShader.markText(atlas,left,top,width*density,height*density,y-1);
      }}finally{g.dispose();}
      ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(atlas,"png",out);pngs.put(width,out.toByteArray());
    }
    return new Baked(source.advances,pngs,groups,height);
  }
}
