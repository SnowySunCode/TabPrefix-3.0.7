package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable vector artwork. Coordinates are GUI pixels; paths use a normalized 0..1000 canvas. */
public final class DisplayArtwork {
  public final Paint panel;
  public final int panelHeight;
  public final Placement title;
  public final List<Placement> rows;
  public final List<Box> boxes;
  public final List<Shape> shapes;
  private final java.util.concurrent.ConcurrentMap<String,String> signatures=new java.util.concurrent.ConcurrentHashMap<>();

  public DisplayArtwork(JsonObject j) {
    panel = new Paint(obj(j,"panel"),true);
    panelHeight = DisplayDesign.integer(j,"panelHeight",0,0,512);
    title = new Placement(obj(j,"title"));
    rows = list(j,"rows",15,e -> new Placement(e));
    boxes = list(j,"boxes",104,e -> new Box(e));
    shapes = list(j,"shapes",64,e -> new Shape(e));
    Set<String> keys = new HashSet<>(), ids = new HashSet<>();
    for (Box b : boxes) if(!keys.add(b.key())) throw bad("Duplicate artwork box");
    for (Shape s : shapes) {
      if(!ids.add(s.id)) throw bad("Duplicate shape identifier");
      if(!s.surface.equals("SIDEBAR") && box(s.surface,s.index)==null)
        throw bad("Shape requires a matching TAB box");
    }
  }
  interface Parser<T> { T parse(JsonObject j); }
  static <T> List<T> list(JsonObject j,String key,int max,Parser<T> parser) {
    List<T> values = new ArrayList<>();
    if(j.has(key)) {
      if(!j.get(key).isJsonArray() || j.getAsJsonArray(key).size()>max) throw bad(key+": too many elements");
      for(JsonElement e:j.getAsJsonArray(key)) values.add(parser.parse(DisplayDesign.object(e,key)));
    }
    return Collections.unmodifiableList(values);
  }
  static JsonObject obj(JsonObject j,String key) { return j.has(key)?DisplayDesign.object(j.get(key),key):new JsonObject(); }
  static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
  static String color(JsonObject j,String key,String fallback) {
    String s=DisplayDesign.string(j,key,fallback,16);
    if(!s.equals("none")&&!s.matches("#[0-9a-fA-F]{6}")) throw bad(key+": use #RRGGBB or none");
    return s.toLowerCase(Locale.ROOT);
  }
  public static final class Paint {
    public final String fill,end,gradient,stroke,accent;
    public final int opacity,radius,accentWidth;
    public final float strokeWidth;
    public Paint(JsonObject j,boolean panel) {
      fill=color(j,"fill",panel?"#0e1623":"#27263d");end=color(j,"end",fill);
      gradient=DisplayDesign.choice(j,"gradient","VERTICAL","VERTICAL","HORIZONTAL");
      stroke=color(j,"stroke","none");accent=color(j,"accent",panel?"#68dec0":"none");
      opacity=DisplayDesign.integer(j,"opacity",panel?75:90,0,100);
      strokeWidth=(float)DisplayDesign.decimal(j,"strokeWidth",1,0,16);
      radius=DisplayDesign.integer(j,"radius",4,0,64);
      accentWidth=DisplayDesign.integer(j,"accentWidth",panel?2:0,0,16);
    }
    public JsonObject json() {
      JsonObject j=new JsonObject();j.addProperty("fill",fill);j.addProperty("end",end);j.addProperty("gradient",gradient);
      j.addProperty("stroke",stroke);j.addProperty("accent",accent);j.addProperty("opacity",opacity);
      j.addProperty("strokeWidth",strokeWidth);j.addProperty("radius",radius);j.addProperty("accentWidth",accentWidth);return j;
    }
  }
  public static final class Placement {
    public final int x,y,size,width;
    public final String align;
    public Placement(JsonObject j) {
      x=DisplayDesign.integer(j,"x",7,-400,400);y=DisplayDesign.integer(j,"y",-1,-1,512);
      size=DisplayDesign.integer(j,"size",0,0,32);if(size>0&&size<4) throw bad("Text size: 4..32 or 0 for inherited size");
      width=DisplayDesign.integer(j,"width",0,0,400);align=DisplayDesign.choice(j,"align","LEFT","LEFT","CENTER","RIGHT");
    }
    public int size(DisplayDesign d) { return size==0?d.sidebarFontSize:size; }
    public int y(DisplayDesign d,int index) { return y<0?5+(index+1)*d.sidebarLineHeight:y; }
    public int width(DisplayDesign d) { return width==0?Math.max(1,d.sidebarWidth-14):width; }
    public int left(int textWidth,int available) { return x+(align.equals("CENTER")?(available-textWidth)/2:align.equals("RIGHT")?available-textWidth:0); }
    public JsonObject json() { JsonObject j=new JsonObject();j.addProperty("x",x);j.addProperty("y",y);j.addProperty("size",size);j.addProperty("width",width);j.addProperty("align",align);return j; }
  }
  public static final class Box {
    public final String surface,align,fit;
    public final int index,width,height,padding;
    public final float inset;
    public final Paint paint;
    public final boolean adaptive;
    public Box(JsonObject j) {
      surface=DisplayDesign.choice(j,"surface","HEADER","HEADER","FOOTER","CELL");
      fit=DisplayDesign.choice(j,"fit","ROW","ROW","CONTENT");
      adaptive=DisplayDesign.bool(j,"adaptive",surface.equals("CELL"));
      index=DisplayDesign.integer(j,"index",0,0,surface.equals("CELL")?79:11);
      width=DisplayDesign.integer(j,"width",surface.equals("CELL")?120:240,16,400);
      height=DisplayDesign.integer(j,"height",surface.equals("CELL")?8:26,8,surface.equals("CELL")?8:64);
      padding=DisplayDesign.integer(j,"padding",surface.equals("CELL")?2:8,0,32);
      inset=(float)DisplayDesign.decimal(j,"inset",surface.equals("CELL")?.5:0,0,3);
      if(padding*2>=width) throw bad("Box padding leaves no room for text");
      align=DisplayDesign.choice(j,"align","CENTER","LEFT","CENTER","RIGHT");paint=new Paint(obj(j,"paint"),false);
    }
    public String key() { return "tab:"+surface.toLowerCase(Locale.ROOT)+":"+index; }
    public int textY() { return (height-8)/2; }
    public int extraLines() { return Math.max(0,(height+8)/9-1); }
    public JsonObject json() { JsonObject j=new JsonObject();j.addProperty("surface",surface);j.addProperty("index",index);j.addProperty("width",width);j.addProperty("height",height);j.addProperty("padding",padding);j.addProperty("inset",inset);j.addProperty("align",align);j.addProperty("fit",fit);j.addProperty("adaptive",adaptive);j.add("paint",paint.json());return j; }
  }
  public static final class Point {
    public final int x,y;
    /** Start a separate stroke; older points continue the previous contour. */
    public final boolean move;
    public Point(JsonObject j) { x=DisplayDesign.integer(j,"x",0,0,1000);y=DisplayDesign.integer(j,"y",0,0,1000);move=DisplayDesign.bool(j,"move",false); }
    JsonObject json() { JsonObject j=new JsonObject();j.addProperty("x",x);j.addProperty("y",y);if(move)j.addProperty("move",true);return j; }
  }
  public static final class Shape {
    public final String id,surface,kind;
    public final int index,x,y,width,height;
    public final boolean enabled,closed;
    public final Paint paint;
    public final List<Point> points;
    public Shape(JsonObject j) {
      id=DisplayDesign.string(j,"id","",48);if(!id.matches("[A-Za-z0-9_-]{1,48}")) throw bad("Shape: invalid identifier");
      surface=DisplayDesign.choice(j,"surface","SIDEBAR","SIDEBAR","HEADER","FOOTER","CELL");
      index=DisplayDesign.integer(j,"index",0,0,surface.equals("CELL")?79:surface.equals("SIDEBAR")?0:11);
      kind=DisplayDesign.choice(j,"kind","ROUND_RECT","RECT","ROUND_RECT","ELLIPSE","LINE","POLYGON","PATH");
      x=DisplayDesign.integer(j,"x",0,-512,512);y=DisplayDesign.integer(j,"y",0,-512,512);
      width=DisplayDesign.integer(j,"width",80,1,512);height=DisplayDesign.integer(j,"height",24,1,512);
      enabled=DisplayDesign.bool(j,"enabled",true);closed=DisplayDesign.bool(j,"closed",true);
      // The authenticated HTTP body limit bounds payloads. Do not truncate freehand strokes.
      paint=new Paint(obj(j,"paint"),false);points=list(j,"points",Integer.MAX_VALUE,e -> new Point(e));
      if((kind.equals("POLYGON")||kind.equals("PATH"))&&points.size()<(closed?3:2)) throw bad("Contour requires at least "+(closed?3:2)+" points");
    }
    public JsonObject json() {
      JsonObject j=new JsonObject();j.addProperty("id",id);j.addProperty("surface",surface);j.addProperty("index",index);j.addProperty("kind",kind);
      j.addProperty("x",x);j.addProperty("y",y);j.addProperty("width",width);j.addProperty("height",height);
      j.addProperty("enabled",enabled);j.addProperty("closed",closed);j.add("paint",paint.json());JsonArray a=new JsonArray();for(Point p:points)a.add(p.json());j.add("points",a);return j;
    }
  }
  public Box box(String surface,int index) { for(Box b:boxes)if(b.surface.equals(surface)&&b.index==index)return b;return null; }
  public Placement row(int index) { return index<0?title:index<rows.size()?rows.get(index):new Placement(new JsonObject()); }
  public int height(DisplayDesign d) {
    if(panelHeight>0)return panelHeight;
    int h=(d.sidebar.size()+1)*d.sidebarLineHeight+10;
    for(int n=-1;n<d.sidebar.size();n++) { Placement p=row(n);h=Math.max(h,p.y(d,n)+p.size(d)+5); }
    return Math.min(512,h);
  }
  public boolean sidebarGraphic(DisplayDesign d) { return d.sidebarBackground||shapes.stream().anyMatch(s->s.enabled&&s.surface.equals("SIDEBAR")); }
  public int rowY(DisplayDesign d,int index) { return Math.max(-512,d.sidebarY-5)+row(index).y(d,index); }
  public void validate(DisplayDesign d) {
    if(d.sidebarMode.equals("HUD"))for(int n=-1;n<d.sidebar.size();n++) if(rowY(d,n)>512) throw bad("Artwork row extends beyond the HUD canvas");
    for(Box b:boxes) if(!b.surface.equals("CELL")&&b.index>=(b.surface.equals("HEADER")?d.header:d.footer).size()) throw bad("TAB artwork refers to a missing line");
  }
  public String signature(DisplayDesign d,Box box) {
    String key=box==null?"sidebar":box.key();String cached=signatures.get(key);if(cached!=null)return cached;
    JsonObject j=new JsonObject();String surface=box==null?"SIDEBAR":box.surface;int index=box==null?0:box.index;
    if(box==null) { j.add("paint",panel.json());j.addProperty("visible",d.sidebarBackground);j.addProperty("width",d.sidebarWidth);j.addProperty("height",height(d)); }
    else j.add("box",box.json());
    JsonArray a=new JsonArray();for(Shape s:shapes)if(s.enabled&&s.surface.equals(surface)&&s.index==index)a.add(s.json());j.add("shapes",a);
    try { byte[] hash=MessageDigest.getInstance("SHA-256").digest(j.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("art:");for(byte v:hash)out.append(String.format(Locale.ROOT,"%02x",v&255));String signature=out.toString();signatures.putIfAbsent(key,signature);return signature; }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
  public JsonObject json() {
    JsonObject j=new JsonObject();j.add("panel",panel.json());j.addProperty("panelHeight",panelHeight);j.add("title",title.json());
    JsonArray a=new JsonArray();for(Placement p:rows)a.add(p.json());j.add("rows",a);a=new JsonArray();for(Box b:boxes)a.add(b.json());j.add("boxes",a);a=new JsonArray();for(Shape s:shapes)a.add(s.json());j.add("shapes",a);return j;
  }
}
