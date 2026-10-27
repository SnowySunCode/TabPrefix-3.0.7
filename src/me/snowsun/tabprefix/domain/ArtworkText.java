package me.snowsun.tabprefix.domain;

/** Uses delivered glyph metrics for overlays and alignment. Missing revisions always use plain text. */
public final class ArtworkText {
  private ArtworkText() {}
  public static String tab(NativeHudLayout hud,DisplayDesign d,String surface,int index,String legacy) {
    DisplayArtwork.Box box=d.artwork.box(surface,index);if(box==null)return legacy;
    NativeHudLayout.Profile image=hud.get(box.key(),0,8,d.artwork.signature(d,box));
    NativeHudLayout.Profile font=hud.get(surface.equals("CELL")?"tab:text":box.key()+":text",box.textY(),8,"");
    if(image==null||font==null)return legacy;
    NativeHudLayout.Encoded text=NativeHudLayout.encode(font,legacy,hud.foreignWidths,box.adaptive?Integer.MAX_VALUE:box.width-2*box.padding);
    int frame=box.adaptive?Math.min(image.frames()-1,Math.max(0,(text.width+2*box.padding-box.width+15)/16)):0;
    int width=Math.min(400,box.width+frame*16),advance=Math.max(width,box.adaptive?text.width+2*box.padding:width);
    int x=box.align.equals("CENTER")?(width-text.width)/2:box.align.equals("RIGHT")?width-box.padding-text.width:box.padding;
    String out=NativeHudLayout.position(new NativeHudLayout.Encoded("§f"+image.glyph(frame),image.advance(frame)),0)
        +NativeHudLayout.position(text,x)+NativeHudLayout.shift(advance);
    if(!surface.equals("CELL"))for(int n=0;n<box.extraLines();n++)out+="\n ";
    return out;
  }

 /** The background spans vanilla's head/name/ping rectangle; only the name advances the cursor. */
 public static String cell(NativeHudLayout hud,DisplayDesign d,int index,String legacy,int limit,int columns,boolean heads,long tick,boolean animate){
  DisplayArtwork.Box box=d.artwork.box("CELL",index);
  if(box==null||!box.fit.equals("ROW"))return compact(hud,d,"CELL",index,legacy,limit,tick,animate);
  NativeHudLayout.Profile image=hud.get(box.key()+":row",0,8,NativeHudLayout.compactSignature(d,box)),font=hud.get("tab:compact",0,7,"");
  if(image==null||font==null)return compact(hud,d,"CELL",index,legacy,limit,tick,animate);
  int frame=(Math.max(1,Math.min(4,columns))-1)*2+(heads?1:0),padding=Math.min(box.padding,Math.max(0,(limit-8)/2));
  String text=TabMetrics.fit(TabMetrics.compactText(font,legacy,hud.metrics).text,hud.metrics,Math.max(1,limit-2*padding),tick,animate,NativeHudLayout.encode(font,"..").text);
  int width=TabMetrics.width(text,hud.metrics),x=box.align.equals("CENTER")?(limit-width)/2:box.align.equals("RIGHT")?limit-padding-width:padding;
  return NativeHudLayout.position(new NativeHudLayout.Encoded("§f"+image.glyph(frame),image.advance(frame)),heads?-9:0)
      +NativeHudLayout.position(new NativeHudLayout.Encoded(text,width),x)+NativeHudLayout.shift(limit);
 }

 public static String compact(NativeHudLayout hud,DisplayDesign d,String surface,int index,String legacy,int limit,long tick,boolean animate){
  NativeHudLayout.Profile common=hud.get("tab:compact",0,7,"");String dots=common==null?"..":NativeHudLayout.encode(common,"..").text;
  DisplayArtwork.Box box=d.artwork.box(surface,index);if(box==null)return TabMetrics.fit(legacy,hud.metrics,limit,tick,animate,dots);
  NativeHudLayout.Profile image=hud.get(box.key()+":compact",0,8,NativeHudLayout.compactSignature(d,box));int h=surface.equals("CELL")?8:Math.min(box.height,Math.max(9,(d.tabBandRows-1)*9));
  NativeHudLayout.Profile font=hud.get(surface.equals("CELL")?"tab:compact":box.key()+":compact:text",(h-8)/2,surface.equals("CELL")?7:8,"");
  if(image==null||font==null)return TabMetrics.fit(legacy,hud.metrics,limit,tick,animate,dots);
  int frame=0,w=Math.min(box.width,Math.min(limit,d.tabWidth));
  if(surface.equals("CELL")){int[] widths=TabMetrics.compactBoxWidths(d,box);int needed=Math.min(limit,Math.max(Math.min(box.width,limit),box.adaptive?TabMetrics.width(legacy,hud.metrics)+box.padding*2:0));for(int n=0;n<Math.min(image.frames(),widths.length);n++){if(widths[n]>limit)break;frame=n;w=widths[n];if(w>=needed)break;}}
  int padding=Math.min(box.padding,Math.max(0,(w-8)/2));NativeHudLayout.Encoded encoded=TabMetrics.compactText(font,legacy,hud.metrics);
  String text=TabMetrics.fit(encoded.text,hud.metrics,Math.max(1,w-2*padding),tick,animate,NativeHudLayout.encode(font,"..").text);int textWidth=TabMetrics.width(text,hud.metrics);
  int x=box.align.equals("CENTER")?(w-textWidth)/2:box.align.equals("RIGHT")?w-padding-textWidth:padding;
  String out=NativeHudLayout.position(new NativeHudLayout.Encoded("§f"+image.glyph(frame),image.advance(frame)),0)+NativeHudLayout.position(new NativeHudLayout.Encoded(text,textWidth),x)+NativeHudLayout.shift(w);
  if(!surface.equals("CELL"))for(int n=0;n<Math.max(0,(h+8)/9-1);n++)out+="\n ";return out;
 }
}
