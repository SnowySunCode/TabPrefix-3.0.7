package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;

/** Private fonts; HD text, artwork and media share the same versioned sampling path. */
public final class NativeHudFonts {
  private static final Font HUD_FONT = loadFont("DejaVuSans.ttf",Font.PLAIN);
  private static final Font HUD_BOLD_FONT = loadFont("DejaVuSans-Bold.ttf",Font.BOLD);
  private static Font loadFont(String name,int style) {
    try (InputStream in=NativeHudFonts.class.getResourceAsStream("/fonts/"+name)) {
      return Font.createFont(Font.TRUETYPE_FONT,in).deriveFont(style);
    } catch (Exception e) { return new Font(Font.SANS_SERIF,style,16); }
  }
  /** Bound live vector frames before selecting 8x sampling; media and text keep their own density. */
  private static long artworkPixels(DisplayDesign d) {
    long pixels=d.sidebarEnabled&&d.sidebarMode.equals("HUD")&&d.artwork.sidebarGraphic(d)?(long)d.sidebarWidth*d.artwork.height(d):0;
    boolean compact=d.layoutEnabled||d.tabLayoutMode.equals("COMPACT")&&(d.headerEnabled||d.footerEnabled);
    for(DisplayArtwork.Box box:d.artwork.boxes) {
      pixels+=(long)box.width*box.height;
      if(box.adaptive)for(int w=box.width+16;w<416;w+=16){pixels+=(long)Math.min(400,w)*box.height;if(w>=400)break;}
      if(!compact)continue;
      if(box.surface.equals("CELL")) {
        for(int width:TabMetrics.compactBoxWidths(d,box))pixels+=(long)(width-1)*9;
        if(box.fit.equals("ROW"))for(int cols=1;cols<=4;cols++)for(int heads=0;heads<=1;heads++)pixels+=(long)(TabMetrics.contentWidth(d.tabWidth,cols,d.tabColumnGap)+13+heads*9)*8;
      }else pixels+=(long)(Math.min(box.width,d.tabWidth)-1)*Math.min(box.height,Math.max(9,(d.tabBandRows-1)*9));
    }
    return pixels;
  }
  private static final class Request {
    final String id;
    final int y, height;
    final AssetDescriptor image;
    final BufferedImage bitmap;
    final String signature;
    int density=1;
    final java.util.List<BufferedImage> variants;

    Request(String id, int y, int height, AssetDescriptor image) {
      this.id = id;
      this.y = y;
      this.height = height;
      this.image = image;
      this.bitmap=null;this.variants=Collections.emptyList();this.signature=image==null?"":image.id.toString();
    }
    Request(String id,int y,int height,BufferedImage bitmap,String signature) {
      this.id=id;this.y=y;this.height=height;this.image=null;this.bitmap=bitmap;this.variants=Collections.singletonList(bitmap);this.signature=signature;
    }
    Request(String id,int y,int height,java.util.List<BufferedImage> variants,String signature){this.id=id;this.y=y;this.height=height;this.image=null;this.variants=variants;this.bitmap=variants.get(0);this.signature=signature;}
    Request(String id,int y,int height,BufferedImage bitmap,String signature,int density){this(id,y,height,bitmap,signature);this.density=density;}
    Request(String id,int y,int height,java.util.List<BufferedImage> frames,String signature,int density){this(id,y,height,frames,signature);this.density=density;}
  }

  public static NativeHudLayout generate(
      Map<String, byte[]> entries,
      JsonArray providers,
      DisplayDesign d,
      GraphicSnapshot assets,
      MediaStore media,
      MinecraftVersion version)
      throws IOException {
    if (d == null || !version.bitmapFonts()) return NativeHudLayout.EMPTY;
    boolean hd=HudShader.enabled(d,version);
    int artDensity=hd&&artworkPixels(d)*64<=24_000_000L?8:4;
    java.util.List<Request> requests = new ArrayList<>();
    if (d.screenEnabled)
      for (DisplayDesign.Screen s : d.screen)
        if (s.enabled && s.anchor.equals("FREE_XY")) {
          AssetDescriptor a = s.image == null ? null : assets.assets.get(s.image);
          if (s.kind.equals("TEXT") || a != null && a.assigned())
            requests.add(new Request("screen:" + s.id, s.y, s.height, a));
        }
    if (d.sidebarEnabled && d.sidebarMode.equals("HUD")) {
      if (d.artwork.sidebarGraphic(d)) requests.add(new Request("sidebar:background",Math.max(-512,d.sidebarY-5),d.sidebarFontSize,ArtworkRasterizer.render(d,null,d.sidebarWidth,d.artwork.height(d),artDensity),d.artwork.signature(d,null),artDensity));
      requests.add(new Request("sidebar:title", d.artwork.rowY(d,-1), d.artwork.title.size(d), null));
      // Spare profiles keep new scoreboard rows visible before the rebuilt pack is delivered.
      for (int n = 0; n < 15; n++)
        if (d.artwork.rowY(d,n) <= 512)
          requests.add(new Request("sidebar:" + n, d.artwork.rowY(d,n), d.artwork.row(n).size(d), null));
    }
    for(DisplayArtwork.Box box:d.artwork.boxes) {
      java.util.List<BufferedImage> variants=new ArrayList<>();variants.add(ArtworkRasterizer.render(d,box,box.width,box.height,artDensity));if(box.adaptive)for(int w=box.width+16;w<416;w+=16){variants.add(ArtworkRasterizer.render(d,box,Math.min(400,w),box.height,artDensity));if(w>=400)break;}requests.add(new Request(box.key(),0,8,variants,d.artwork.signature(d,box),artDensity));
      if(!box.surface.equals("CELL")) requests.add(new Request(box.key()+":text",box.textY(),8,null));
    }
    if(d.artwork.boxes.stream().anyMatch(box->box.surface.equals("CELL"))) requests.add(new Request("tab:text",0,8,null));
    if(d.layoutEnabled||d.tabLayoutMode.equals("COMPACT")&&(d.headerEnabled||d.footerEnabled)){
      requests.add(new Request("tab:compact",0,7,null));
      for(DisplayArtwork.Box box:d.artwork.boxes){java.util.List<BufferedImage> variants=new ArrayList<>();
        if(box.surface.equals("CELL")){for(int width:TabMetrics.compactBoxWidths(d,box))variants.add(ArtworkRasterizer.render(d,box,width-1,9,artDensity));
          if(box.fit.equals("ROW")){java.util.List<BufferedImage> rows=new ArrayList<>();for(int cols=1;cols<=4;cols++)for(int heads=0;heads<=1;heads++){
            int width=TabMetrics.contentWidth(d.tabWidth,cols,d.tabColumnGap)+13+heads*9;
            BufferedImage row=ArtworkRasterizer.render(d,box,width,8,artDensity,0);
            // Faces are drawn before names by vanilla. Preserve that already rendered 8x8 region.
            if(heads==1){Graphics2D rg=row.createGraphics();try{rg.setComposite(AlphaComposite.Clear);rg.fillRect(0,0,8*artDensity,8*artDensity);}finally{rg.dispose();}}
            rows.add(row);
          }requests.add(new Request(box.key()+":row",0,8,rows,NativeHudLayout.compactSignature(d,box),artDensity));}
        }
        else{int h=Math.min(box.height,Math.max(9,(d.tabBandRows-1)*9));variants.add(ArtworkRasterizer.render(d,box,Math.min(box.width,d.tabWidth)-1,h,artDensity));requests.add(new Request(box.key()+":compact:text",(h-8)/2,8,null));}
        requests.add(new Request(box.key()+":compact",0,8,variants,NativeHudLayout.compactSignature(d,box),artDensity));
      }
      Set<UUID> used=new TreeSet<>();for(GraphicPrefix p:assets.prefixes.values())if(p.assetId!=null)used.add(p.assetId);for(DisplayDesign.Slot slot:d.slots)if(slot.image!=null)used.add(slot.image);
      for(DisplayDesign.GroupStyle g:d.groupStyles){if(g.prefixImage!=null)used.add(g.prefixImage);if(g.suffixImage!=null)used.add(g.suffixImage);for(DisplayDesign.AffixPart p:g.prefixParts)if(p.image!=null)used.add(p.image);for(DisplayDesign.AffixPart p:g.suffixParts)if(p.image!=null)used.add(p.image);}
      int budget=0;Set<String> keys=new HashSet<>();for(Request r:requests){if(r.bitmap!=null)budget+=r.variants.size();else if(r.image!=null)budget+=r.image.frames();else if(keys.add(r.y+":"+r.height))budget+=NativeHudLayout.ALPHABET.length();}if(keys.add("0:8"))budget+=NativeHudLayout.ALPHABET.length();
      for(UUID id:used){AssetDescriptor a=assets.assets.get(id);if(a==null||!a.assigned()||requests.size()>=508||budget+a.frames()>=NativeHudLayout.SPACE-NativeHudLayout.FIRST)continue;
        java.util.List<BufferedImage> frames=new ArrayList<>();int maxWidth=Math.max(1,Math.min(40,TabMetrics.contentWidth(d.tabWidth,4,d.tabColumnGap)-9));double factor=Math.min(8.0/a.height,maxWidth/(double)a.width);int w=Math.max(1,(int)Math.floor(a.width*factor)),h=Math.max(1,(int)Math.floor(a.height*factor));
        int density=hd?4:1;
        for(int n=0;n<a.frames();n++){BufferedImage tile=new BufferedImage(w*density,8*density,BufferedImage.TYPE_INT_ARGB);Graphics2D g=tile.createGraphics();try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,hd?RenderingHints.VALUE_INTERPOLATION_BICUBIC:RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(media.frame(a,n),0,(8-h)*density/2,w*density,h*density,null);}finally{g.dispose();}reserveCanvas(tile);frames.add(tile);}
        requests.add(new Request("tab:image:"+id,0,8,frames,id.toString(),density));budget+=a.frames();
      }
    }
    if (requests.isEmpty()&&!(d.layoutEnabled&&d.tabAdaptive)) return NativeHudLayout.EMPTY;
    requests.add(new Request("action", 0, 8, null));
    spaces(entries, providers, version);
    Map<String, int[]> widthsBySize = new HashMap<>();
    Map<String, java.util.List<JsonObject>> fonts = new HashMap<>();
    Map<String, NativeHudLayout.Profile> profiles = new LinkedHashMap<>();
    Map<String,NativeHudLayout.Profile> aliases=new HashMap<>();
    int profile = 0, nextCode=NativeHudLayout.FIRST;
    for (Request r : requests) {
      if(r.bitmap==null&&r.image==null){NativeHudLayout.Profile shared=aliases.get(r.y+":"+r.height);if(shared!=null){profiles.put(r.id,shared.alias(r.id));continue;}}
      int base=nextCode;profile++;
      nextCode += r.bitmap!=null?r.variants.size():r.image==null?NativeHudLayout.ALPHABET.length():r.image.frames();
      if(nextCode>=NativeHudLayout.SPACE)throw new IOException("Artwork exceeds the private font budget");
      int[] widths,boldWidths=null;
      boolean background = r.bitmap!=null;
      int panelHeight = background?r.bitmap.getHeight()/r.density:0;
      int logicalHeight = Math.max(Math.max(40, 8 - r.y),background?panelHeight:0),
          imageWidth =
              r.image == null
                  ? background ? r.variants.stream().mapToInt(frame->frame.getWidth()/r.density).max().orElse(0) : 0
                  : Math.min(
                      510,
                      Math.max(
                          1, (int) Math.round((double) r.image.width * r.height / r.image.height)));
      int scale = 1;
      while ((logicalHeight + scale - 1) / scale > 256 || (imageWidth + scale - 1) / scale > 256) {
        scale++;
        while (r.height % scale != 0) scale++;
      }
      int cellHeight = (logicalHeight + scale - 1) / scale, renderHeight = cellHeight * scale;
      // Spend available cell resolution on readable text. Client cells remain <= 256px.
      int raster = r.image == null ? Math.max(1, Math.min(4, 256 / cellHeight)) : 1;
      String key = "v2:" + r.height + ":" + cellHeight + ":" + scale + ":" + raster;
      if(background&&hd&&r.id.startsWith("tab:image:")) {
        // A TAB icon fits one HD cell. Keep it atomic for clipping and readable previews.
        widths=new int[r.variants.size()];
        for(int frame=0;frame<widths.length;frame++){
          BufferedImage source=r.variants.get(frame);int width=source.getWidth()/r.density;
          BufferedImage glyph=new BufferedImage(width*4,40,BufferedImage.TYPE_INT_ARGB);Graphics2D g=glyph.createGraphics();
          try{g.setComposite(AlphaComposite.Src);g.drawImage(source,0,4,width*4,32,null);}finally{g.dispose();}
          HudShader.mark(glyph,-1,true);String file="hud-icon-"+(profile-1)+"-"+frame+".png";png(entries,file,glyph);
          JsonObject p=provider(file,10);p.addProperty("ascent",7);p.add("chars",rows(base+frame,1,1));providers.add(p);widths[frame]=width+1;
        }
      } else if (background) {
        widths=new int[r.variants.size()];for(int frame=0;frame<widths.length;frame++){BufferedImage source=r.variants.get(frame);int w=Math.max(1,(source.getWidth()/r.density+scale-1)/scale);BufferedImage tile=new BufferedImage(w,cellHeight,BufferedImage.TYPE_INT_ARGB);Graphics2D g=tile.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,w,(panelHeight+scale-1)/scale,null);}finally{g.dispose();}reserveCanvas(tile);String file="hud-art-"+(profile-1)+"-"+frame+".png";png(entries,file,tile);JsonObject provider=provider(file,renderHeight);provider.addProperty("ascent",7-r.y);provider.add("chars",rows(base+frame,1,1));providers.add(provider);widths[frame]=advance(tile,0,0,w,cellHeight,scale);}
      } else if (r.image == null && hd) {
        widths=HudTextAtlas.generate(entries,providers,HUD_FONT,base,r.y,r.height);
        boldWidths=HudTextAtlas.generate(entries,providers,HUD_BOLD_FONT,base+NativeHudLayout.BOLD_OFFSET,r.y,r.height);
      } else if (r.image == null) {
        if (!fonts.containsKey(key)) {
          java.util.List<JsonObject> fs = new ArrayList<>();
          widthsBySize.put(key, text(entries, fs, HUD_FONT, r.height, cellHeight, scale, raster));
          fonts.put(key, fs);
        }
        widths = widthsBySize.get(key);
        for (JsonObject original : fonts.get(key)) {
          JsonObject p = new JsonParser().parse(original.toString()).getAsJsonObject();
          int start = p.remove("start").getAsInt(), count = p.remove("count").getAsInt();
          p.addProperty("ascent", 7 - r.y);
          p.add("chars", rows(base + start, count, 16));
          providers.add(p);
        }
        String boldKey=key+":bold";
        if(!fonts.containsKey(boldKey)){
          java.util.List<JsonObject> fs=new ArrayList<>();
          widthsBySize.put(boldKey,text(entries,fs,HUD_BOLD_FONT,r.height,cellHeight,scale,raster));fonts.put(boldKey,fs);
        }
        boldWidths=widthsBySize.get(boldKey);
        for(JsonObject original:fonts.get(boldKey)){
          JsonObject p=new JsonParser().parse(original.toString()).getAsJsonObject();
          int start=p.remove("start").getAsInt(),count=p.remove("count").getAsInt();
          p.addProperty("ascent",7-r.y);p.add("chars",rows(base+NativeHudLayout.BOLD_OFFSET+start,count,16));providers.add(p);
        }
      } else {
        if (r.image.frames() > NativeHudLayout.STRIDE)
          throw new IOException("Too many canvas frames");
        widths = new int[r.image.frames()];
        for (int frame = 0; frame < widths.length; frame++) {
          BufferedImage source = media.frame(r.image, frame);
          int w =
              Math.min(
                  510,
                  Math.max(
                      1,
                      (int)
                          Math.round((double) source.getWidth() * r.height / source.getHeight())));
          int pixelWidth = Math.max(1, w / scale);
          BufferedImage tile =
              new BufferedImage(pixelWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = tile.createGraphics();
          try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, 0, 0, pixelWidth, r.height / scale, null);
          } finally {
            g.dispose();
          }
          // Reserve the authored canvas across GIF frames without a visible border pixel.
          reserveCanvas(tile);
          widths[frame] = advance(tile, 0, 0, pixelWidth, cellHeight, scale);
          String file = "hud-image-" + (profile - 1) + "-" + frame + ".png";
          png(entries, file, tile);
          JsonObject p = provider(file, renderHeight);
          p.addProperty("ascent", 7 - r.y);
          p.add("chars", rows(base + frame, 1, 1));
          providers.add(p);
        }
      }
      profiles.put(
          r.id,
          new NativeHudLayout.Profile(
              r.id, r.signature, base, r.y, r.height, widths,null,boldWidths));
      if(r.bitmap==null&&r.image==null)aliases.put(r.y+":"+r.height,profiles.get(r.id));
    }
    Map<Integer,Integer> tiles=new TreeMap<>();Map<String,Integer> sharedTiles=new HashMap<>();
    for(Request r:requests) {
      if(r.bitmap!=null&&r.density>1&&!(hd&&r.id.startsWith("tab:image:")))nextCode=ArtworkTiles.bake(entries,providers,tiles,profiles.get(r.id),r.variants,r.density,nextCode,sharedTiles,profiles,hd);
      else if(hd&&r.image!=null) {
        java.util.List<BufferedImage> frames=new ArrayList<>();
        for(int n=0;n<r.image.frames();n++)frames.add(hdImage(media.frame(r.image,n),r.height));
        nextCode=ArtworkTiles.bake(entries,providers,tiles,profiles.get(r.id),frames,4,nextCode,sharedTiles,profiles,true);
      }
    }
    Map<Integer,Integer> foreign=new TreeMap<>();
    for(AssetDescriptor a:assets.assets.values())if(a.assigned())
      for(int n=0;n<a.frames();n++) { BufferedImage frame=media.frame(a,n);foreign.put(a.glyphCode(n),advance(frame,0,0,frame.getWidth(),frame.getHeight(),a.renderHeight/(double)a.height)); }
    if(hd)HudShader.install(entries,version);
    return new NativeHudLayout(profiles,foreign,tiles,d.hudQuality);
  }

  private static void reserveCanvas(BufferedImage image){
    int x=image.getWidth()-1,y=image.getHeight()-1;
    if((image.getRGB(x,y)>>>24)==0)image.setRGB(x,y,0x01000000);
  }

  private static BufferedImage hdImage(BufferedImage source,int height){
    int width=Math.min(510,Math.max(1,(int)Math.round(source.getWidth()*(double)height/source.getHeight())));
    BufferedImage out=new BufferedImage(width*4,height*4,BufferedImage.TYPE_INT_ARGB);Graphics2D g=out.createGraphics();
    try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,out.getWidth(),out.getHeight(),null);}finally{g.dispose();}
    return out;
  }

  private static int[] text(
      Map<String, byte[]> entries,
      java.util.List<JsonObject> fonts,
      Font font,
      int size,
      int cellHeight,
      int scale, int raster)
      throws IOException {
    String chars = NativeHudLayout.ALPHABET;
    int[] widths = new int[chars.length()];
    final int fontSize = Math.max(1, size * raster / scale),
        cw = Math.max(4, fontSize + 4 * raster), cols = 16, capacity = 64;
    int pixelHeight = cellHeight * raster;
    for (int start = 0; start < chars.length(); start += capacity) {
      int count = Math.min(capacity, chars.length() - start), rowCount = (count + cols - 1) / cols;
      BufferedImage image =
          new BufferedImage(cols * cw, rowCount * pixelHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try {
        g.setFont(font.deriveFont((float)fontSize));
        g.setColor(Color.WHITE);
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int baseline=Math.max(fontSize-Math.max(1,raster),g.getFontMetrics().getAscent());
        for(int n=0;n<count;n++){
          int x=n%cols*cw,y=n/cols*pixelHeight;g.setClip(x,y,cw,pixelHeight);
          java.awt.font.GlyphVector glyph=g.getFont().createGlyphVector(g.getFontRenderContext(),new char[]{chars.charAt(start+n)});
          Rectangle bounds=glyph.getPixelBounds(g.getFontRenderContext(),0,baseline);
          g.drawGlyphVector(glyph,x+Math.max(0,1-bounds.x),y+baseline+Math.max(0,1-bounds.y));
        }g.setClip(null);
      } finally {
        g.dispose();
      }
      for (int n = 0; n < count; n++)
        widths[start + n] =
            advance(image, n % cols * cw, n / cols * pixelHeight, cw, pixelHeight, scale / (double) raster);
      String file = "hud-text-v3-" + font.getStyle() + "-" + size + "-" + cellHeight + "-" + scale + "-" + raster + "-" + start + ".png";
      png(entries, file, image);
      JsonObject p = provider(file, cellHeight * scale);
      p.addProperty("start", start);
      p.addProperty("count", count);
      fonts.add(p);
    }
    return widths;
  }

  private static int advance(BufferedImage image, int x, int y, int width, int height, double scale) {
    for (int col = width - 1; col >= 0; col--)
      for (int row = 0; row < height; row++)
        if ((image.getRGB(x + col, y + row) >>> 24) != 0) return (int) Math.floor((col + 1) * scale + .5d) + 1;
    return 1;
  }

  private static JsonObject provider(String file, int height) {
    JsonObject j = new JsonObject();
    j.addProperty("type", "bitmap");
    j.addProperty("file", "tabprefix:font/" + file);
    j.addProperty("height", height);
    return j;
  }

  private static JsonArray rows(int base, int count, int cols) {
    JsonArray a = new JsonArray();
    for (int n = 0; n < count; n += cols) {
      StringBuilder s = new StringBuilder();
      for (int col = 0; col < cols; col++) s.appendCodePoint(n + col < count ? base + n + col : 0);
      a.add(s.toString());
    }
    return a;
  }

  private static void png(Map<String, byte[]> entries, String file, BufferedImage image)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", out)) throw new IOException("PNG writer unavailable");
    entries.put("assets/tabprefix/textures/font/" + file, out.toByteArray());
  }

  private static void spaces(
      Map<String, byte[]> entries, JsonArray providers, MinecraftVersion version)
      throws IOException {
    boolean modern = version.compareTo(MinecraftVersion.parse("1.19")) >= 0;
    JsonObject widths = new JsonObject();
    if (!modern) {
      BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
      pixel.setRGB(0, 0, 0xffffffff);
      png(entries, "hud-space.png", pixel);
    }
    for (int sign = 0; sign < 2; sign++)
      for (int bit = 0; bit < 13; bit++) {
        int width = (1 << bit) * (sign == 0 ? 1 : -1);
        String c = new String(Character.toChars(NativeHudLayout.SPACE + sign * 13 + bit));
        if (modern) widths.addProperty(c, width);
        else {
          JsonObject p = provider("hud-space.png", width > 0 ? width - 1 : width - 2);
          p.addProperty("ascent", -8192);
          JsonArray a = new JsonArray();
          a.add(c);
          p.add("chars", a);
          providers.add(p);
        }
      }
    if (modern) {
      JsonObject p = new JsonObject();
      p.addProperty("type", "space");
      p.add("advances", widths);
      providers.add(p);
    }
  }

  private NativeHudFonts() {}
}
