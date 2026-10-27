package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.domain.NativeHudLayout;

/** Small, densely sampled glyphs compose a large panel without stretching a low-resolution PNG. */
final class ArtworkTiles {
  private ArtworkTiles(){}
  static int count(java.util.List<BufferedImage> frames,int density,boolean hd){int count=0,tw=hd?Math.min(64,256/density-2):64,th=hd?Math.min(32,256/density-2):32;for(BufferedImage f:frames)count+=((f.getWidth()/density+tw-1)/tw)*((f.getHeight()/density+th-1)/th);return count;}
  static int bake(Map<String,byte[]> entries,JsonArray providers,Map<Integer,Integer> metrics,
      NativeHudLayout.Profile original,java.util.List<BufferedImage> frames,int sourceDensity,int firstCode,Map<String,Integer> shared,
      Map<String,NativeHudLayout.Profile> profiles,boolean hd)throws IOException{
    // Very high negative offsets need baseline padding; keep the legal existing fallback there.
    if(!hd&&original.y< -248||firstCode+count(frames,sourceDensity,hd)>=NativeHudLayout.SPACE)return firstCode;
    String[] runs=new String[frames.size()];int[] advances=new int[frames.size()];int code=firstCode;
    int tileWidth=hd?Math.min(64,256/sourceDensity-2):64,tileHeight=hd?Math.min(32,256/sourceDensity-2):32;
    for(int frame=0;frame<frames.size();frame++){
      BufferedImage source=frames.get(frame);int width=source.getWidth()/sourceDensity,height=source.getHeight()/sourceDensity;StringBuilder run=new StringBuilder();
      for(int y=0;y<height;y+=tileHeight)for(int x=0;x<width;x+=tileWidth){
        int w=Math.min(tileWidth,width-x),h=Math.min(tileHeight,height-y),top=original.y+y;
        int cellHeight=hd?Math.max(8,h+2):Math.max(h,Math.max(8,8-top)),density=hd?sourceDensity:Math.max(1,Math.min(4,256/cellHeight)),padding=hd?density:0;
        BufferedImage tile=new BufferedImage((w+(hd?2:0))*density,cellHeight*density,BufferedImage.TYPE_INT_ARGB);Graphics2D g=tile.createGraphics();
        try{if(hd)g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setClip(0,0,tile.getWidth(),hd?(h+2)*density:h*density);g.drawImage(source,padding-x*density,padding-y*density,width*density,height*density,null);}finally{g.dispose();}
        int px=tile.getWidth()-1,py=tile.getHeight()-1;if((tile.getRGB(px,py)>>>24)==0)tile.setRGB(px,py,0x01000000);
        if(hd)HudShader.markGraphicTile(tile,top-1,w,h,density,(x==0?1:0)|(x+w==width?2:0)|(y==0?4:0)|(y+h==height?8:0));
        String key=tile.getWidth()+":"+tile.getHeight()+":"+cellHeight+":"+(hd?7:7-top)+":"+fingerprint(tile);
        Integer glyph=shared.get(key);int advance=w+(hd?3:1);
        if(glyph==null){ByteArrayOutputStream out=new ByteArrayOutputStream();if(!ImageIO.write(tile,"png",out))throw new IOException("PNG writer unavailable");byte[] png=out.toByteArray();glyph=code++;shared.put(key,glyph);String file="hud-tile-"+glyph+".png";entries.put("assets/tabprefix/textures/font/"+file,png);
          JsonObject p=new JsonObject();p.addProperty("type","bitmap");p.addProperty("file","tabprefix:font/"+file);p.addProperty("height",cellHeight);p.addProperty("ascent",hd?7:7-top);JsonArray chars=new JsonArray();chars.add(new String(Character.toChars(glyph)));p.add("chars",chars);providers.add(p);metrics.put(glyph,advance);}
        run.append(NativeHudLayout.position(new NativeHudLayout.Encoded("§f"+new String(Character.toChars(glyph)),advance),x-(hd?1:0)));
      }
      advances[frame]=original.advance(frame);run.append(NativeHudLayout.shift(advances[frame]));runs[frame]=run.toString();
    }
    profiles.put(original.id,new NativeHudLayout.Profile(original.id,original.image,original.base,original.y,original.height,advances,runs));return code;
  }
  /** Hash raw pixels first so shared fragments do not pay for repeated PNG compression. */
  private static String fingerprint(BufferedImage image)throws IOException {
    try {
      java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
      int[] pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();byte[] row=new byte[image.getWidth()*4];
      for(int y=0;y<image.getHeight();y++){
        for(int x=0;x<image.getWidth();x++){int color=pixels[y*image.getWidth()+x],p=x*4;row[p]=(byte)(color>>>24);row[p+1]=(byte)(color>>>16);row[p+2]=(byte)(color>>>8);row[p+3]=(byte)color;}
        digest.update(row);
      }
      return Base64.getEncoder().encodeToString(digest.digest());
    }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
  }
}
