package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;

/** Delivered private bitmap profiles. Ordinary characters and digits are never replaced. */
public final class NativeHudLayout {
  public static final int RENDERER_VERSION = 9;
  public final int rendererVersion;
  public final String quality;
  public final Map<Integer,Integer> metrics;
  public static final int FIRST = 0xf0000, STRIDE = 1024, SPACE = 0xff000;
  /** Bold glyphs use the second private plane without reducing the layout budget. */
  public static final int BOLD_OFFSET = 0x10000;
  public static final String ALPHABET;

  static {
    StringBuilder s = new StringBuilder();
    for (int[] range : new int[][] {{33, 126}, {160, 383}, {1024, 1327}, {8192, 8303}})
      for (int c = range[0]; c <= range[1]; c++) s.append((char) c);
    s.append("★☆♡♥✦✧◆◇●○■□▪▫▲▼↑↓←→✓✕⚔⚙");
    ALPHABET = s.toString();
  }

  public static final NativeHudLayout EMPTY = new NativeHudLayout(Collections.emptyMap());

  public static final class Profile {
    public final String id, image;
    public final int base, boldBase, y, height;
    private final int[] advances;
    private final int[] boldAdvances;
    private final String[] glyphRuns;

    public Profile(String id, String image, int base, int y, int height, int[] advances) {
      this(id,image,base,y,height,advances,null);
    }
    public Profile(String id,String image,int base,int y,int height,int[] advances,String[] glyphRuns) {
      this(id,image,base,y,height,advances,glyphRuns,null);
    }
    public Profile(String id,String image,int base,int y,int height,int[] advances,String[] glyphRuns,int[] boldAdvances) {
      this.id = Objects.requireNonNull(id);
      this.image = Objects.requireNonNull(image);
      this.base = base;
      this.boldBase = base + BOLD_OFFSET;
      this.y = y;
      this.height = height;
      this.advances = advances.clone();
      this.boldAdvances = boldAdvances == null ? null : boldAdvances.clone();
      if(boldAdvances!=null&&(!image.isEmpty()||glyphRuns!=null||boldAdvances.length!=advances.length))
        throw new IllegalArgumentException("Invalid bold HUD profile");
      this.glyphRuns=glyphRuns==null?null:glyphRuns.clone();
      if(glyphRuns!=null){if(glyphRuns.length!=advances.length)throw new IllegalArgumentException("Invalid artwork frames");for(String run:glyphRuns)if(run==null||run.length()>65536)throw new IllegalArgumentException("Invalid artwork frame");}
      if (base < FIRST
          || base + advances.length >= SPACE
          || advances.length == 0
          || advances.length > STRIDE
          || y < -512
          || y > 512
          || height < 4
          || height > 32) throw new IllegalArgumentException("Invalid HUD profile");
      for (int a : advances)
        if (a < 1 || a > 512) throw new IllegalArgumentException("Invalid HUD advance");
      if(boldAdvances!=null)for(int a:boldAdvances)
        if(a<1||a>512)throw new IllegalArgumentException("Invalid bold HUD advance");
    }

    public boolean matches(int y, int height, String image) {
      return this.y == y && this.height == height && this.image.equals(image);
    }

    public int frames() {
      return advances.length;
    }

    public int advance(int index) {
      return advances[index];
    }

    public boolean hasBold() { return boldAdvances != null; }
    public int advance(int index,boolean bold) {
      return bold && hasBold() ? boldAdvances[index] : advances[index] + (bold ? 1 : 0);
    }
    public String glyph(int index,boolean bold) {
      if(index<0||index>=frames())throw new IllegalArgumentException("Invalid HUD glyph");
      return bold && hasBold() ? new String(Character.toChars(boldBase+index)) : glyph(index);
    }
    public Profile alias(String id) {
      return new Profile(id,image,base,y,height,advances,glyphRuns,boldAdvances);
    }

    public String glyph(int index) {
      if (index < 0 || index >= frames()) throw new IllegalArgumentException("Invalid HUD glyph");
      return glyphRuns==null?new String(Character.toChars(base + index)):glyphRuns[index];
    }

    private JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("id", id);
      j.addProperty("image", image);
      j.addProperty("base", base);
      j.addProperty("y", y);
      j.addProperty("height", height);
      JsonArray a = new JsonArray();
      for (int n : advances) a.add(n);
      j.add("advances", a);
      if(hasBold()){JsonArray b=new JsonArray();for(int n:boldAdvances)b.add(n);j.add("boldAdvances",b);}
      if(glyphRuns!=null){JsonArray runs=new JsonArray();for(String run:glyphRuns)runs.add(run);j.add("glyphRuns",runs);}
      return j;
    }
  }

  public final Map<String, Profile> profiles;
  public final Map<Integer,Integer> foreignWidths;
  public final Map<Integer,Integer> bitmapWidths;

  public NativeHudLayout(Map<String, Profile> profiles) {
    this(profiles,Collections.emptyMap());
  }

  public NativeHudLayout(Map<String,Profile> profiles,Map<Integer,Integer> foreignWidths) {
    this(profiles,foreignWidths,Collections.emptyMap(),RENDERER_VERSION,"HD");
  }
  public NativeHudLayout(Map<String,Profile> profiles,Map<Integer,Integer> foreignWidths,Map<Integer,Integer> bitmapWidths){this(profiles,foreignWidths,bitmapWidths,RENDERER_VERSION,"HD");}
  public NativeHudLayout(Map<String,Profile> profiles,Map<Integer,Integer> foreignWidths,Map<Integer,Integer> bitmapWidths,String quality){this(profiles,foreignWidths,bitmapWidths,RENDERER_VERSION,quality);}
  private NativeHudLayout(Map<String,Profile> profiles,Map<Integer,Integer> foreignWidths,Map<Integer,Integer> bitmapWidths,int rendererVersion,String quality) {
    this.rendererVersion = rendererVersion;
    this.quality=quality;
    if (profiles.size() > 512) throw new IllegalArgumentException("Too many HUD profiles");
    this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    this.foreignWidths=Collections.unmodifiableMap(new TreeMap<>(foreignWidths));
    this.bitmapWidths=Collections.unmodifiableMap(new TreeMap<>(bitmapWidths));
    for(Map.Entry<Integer,Integer> e:bitmapWidths.entrySet())if(e.getKey()<FIRST||e.getKey()>=SPACE||e.getValue()<1||e.getValue()>512)throw new IllegalArgumentException("Invalid artwork tile metric");
    Map<Integer,Integer> all=new HashMap<>(foreignWidths);all.putAll(bitmapWidths);for(Profile p:profiles.values())for(int n=0;n<p.frames();n++){all.put(p.base+n,p.advance(n));if(p.hasBold())all.put(p.boldBase+n,p.advance(n,true));}metrics=Collections.unmodifiableMap(all);
    if(foreignWidths.size()>6400)throw new IllegalArgumentException("Too many image metrics");
    for(Map.Entry<Integer,Integer> e:foreignWidths.entrySet())if(e.getKey()<0xe000||e.getKey()>0xf8ff||e.getValue()<1||e.getValue()>512)throw new IllegalArgumentException("Invalid image metric");
  }

  public boolean present() {
    return !profiles.isEmpty();
  }

  public Profile get(String id, int y, int height, String image) {
    Profile p = profiles.get(id);
    return p != null && p.matches(y, height, image) ? p : null;
  }

  public boolean matches(DisplayDesign d) {
    if (present() && (rendererVersion != RENDERER_VERSION||!quality.equals(d.hudQuality))) return false;
    boolean needed = false;
    if(d.layoutEnabled||d.tabLayoutMode.equals("COMPACT")&&(d.headerEnabled||d.footerEnabled)){
      needed=true;if(get("tab:compact",0,7,"")==null)return false;
      for(DisplayArtwork.Box box:d.artwork.boxes)if(get(box.key()+":compact",0,8,compactSignature(d,box))==null)return false;
      for(DisplayArtwork.Box box:d.artwork.boxes)if(box.surface.equals("CELL")&&box.fit.equals("ROW")&&get(box.key()+":row",0,8,compactSignature(d,box))==null)return false;
    }
    if (d.screenEnabled)
      for (DisplayDesign.Screen s : d.screen)
        if (s.enabled && s.anchor.equals("FREE_XY")) {
          needed = true;
          if (get("screen:" + s.id, s.y, s.height, s.image == null ? "" : s.image.toString())
              == null) return false;
        }
    if (d.sidebarEnabled && d.sidebarMode.equals("HUD")) {
      needed = true;
      if (d.artwork.sidebarGraphic(d) && get("sidebar:background",Math.max(-512,d.sidebarY-5),d.sidebarFontSize,d.artwork.signature(d,null))==null)return false;
      for(int n=-1;n<d.sidebar.size();n++)if(get(n<0?"sidebar:title":"sidebar:"+n,d.artwork.rowY(d,n),d.artwork.row(n).size(d),"")==null)return false;
    }
    for(DisplayArtwork.Box box:d.artwork.boxes) {
      needed=true;
      if(get(box.key(),0,8,d.artwork.signature(d,box))==null)return false;
      if(get(box.surface.equals("CELL")?"tab:text":box.key()+":text",box.textY(),8,"")==null)return false;
    }
    return !needed || get("action", 0, 8, "") != null;
  }

  public static String compactSignature(DisplayDesign d,DisplayArtwork.Box box){return d.artwork.signature(d,box)+":"+d.tabWidth+":"+d.tabBandRows+":"+Math.min(8,d.tabColumnGap)+":"+d.hudQuality;}

  public JsonObject json() {
    JsonObject j = new JsonObject();
    j.addProperty("schema", 1);
    j.addProperty("rendererVersion", rendererVersion);
    j.addProperty("quality",quality);
    JsonArray a = new JsonArray();
    for (Profile p : profiles.values()) a.add(p.json());
    j.add("profiles", a);
    JsonObject widths=new JsonObject();for(Map.Entry<Integer,Integer> e:foreignWidths.entrySet())widths.addProperty(String.valueOf(e.getKey()),e.getValue());j.add("foreignWidths",widths);
    JsonObject tiles=new JsonObject();for(Map.Entry<Integer,Integer> e:bitmapWidths.entrySet())tiles.addProperty(String.valueOf(e.getKey()),e.getValue());j.add("bitmapWidths",tiles);
    return j;
  }

  public static NativeHudLayout read(JsonObject j) {
    if (j.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown HUD schema");
    Map<String, Profile> rows = new LinkedHashMap<>();
    for (JsonElement e : j.getAsJsonArray("profiles")) {
      JsonObject p = e.getAsJsonObject();
      JsonArray a = p.getAsJsonArray("advances");
      int[] widths = new int[a.size()];
      for (int n = 0; n < widths.length; n++) widths[n] = a.get(n).getAsInt();
      String[] runs=null;if(p.has("glyphRuns")){JsonArray values=p.getAsJsonArray("glyphRuns");runs=new String[values.size()];for(int n=0;n<runs.length;n++)runs[n]=values.get(n).getAsString();}
      int[] bold=null;if(p.has("boldAdvances")){JsonArray values=p.getAsJsonArray("boldAdvances");bold=new int[values.size()];for(int n=0;n<bold.length;n++)bold[n]=values.get(n).getAsInt();}
      Profile row =
          new Profile(
              p.get("id").getAsString(),
              p.get("image").getAsString(),
              p.get("base").getAsInt(),
              p.get("y").getAsInt(),
              p.get("height").getAsInt(),
              widths,runs,bold);
      if (rows.put(row.id, row) != null)
        throw new IllegalArgumentException("Duplicate HUD profile");
    }
    Map<Integer,Integer> foreign=new TreeMap<>();
    if(j.has("foreignWidths"))for(Map.Entry<String,JsonElement> e:j.getAsJsonObject("foreignWidths").entrySet())foreign.put(Integer.valueOf(e.getKey()),e.getValue().getAsInt());
    Map<Integer,Integer> tiles=new TreeMap<>();if(j.has("bitmapWidths"))for(Map.Entry<String,JsonElement> e:j.getAsJsonObject("bitmapWidths").entrySet())tiles.put(Integer.valueOf(e.getKey()),e.getValue().getAsInt());
    return new NativeHudLayout(rows,foreign,tiles,j.has("rendererVersion") ? j.get("rendererVersion").getAsInt() : 0,j.has("quality")?j.get("quality").getAsString():"COMPATIBLE");
  }

  public static String shift(int amount) {
    if (Math.abs((long) amount) > 65536) throw new IllegalArgumentException("HUD text too wide");
    StringBuilder s = new StringBuilder();
    int n = Math.abs(amount), sign = amount < 0 ? 13 : 0;
    while (n > 0) {
      int bit = Math.min(12, 31 - Integer.numberOfLeadingZeros(n));
      s.appendCodePoint(SPACE + sign + bit);
      n -= 1 << bit;
    }
    return s.toString();
  }

  public static boolean privateGlyph(int c) {
    return c>=FIRST&&c<SPACE||c>=FIRST+BOLD_OFFSET&&c<SPACE+BOLD_OFFSET;
  }

  /** Human-readable editor preview of a delivered run, including actual font weight and spaces. */
  public String readable(String text,java.util.function.Function<UUID,AssetDescriptor> images) {
    StringBuilder out=new StringBuilder(),state=new StringBuilder();int pending=0;
    for(int n=0;n<text.length();) {
      int c=text.codePointAt(n);n+=Character.charCount(c);
      if(c=='§'&&n<text.length()) {
        char code=Character.toLowerCase(text.charAt(n++));String format="§"+code;
        if(code=='x'&&n+12<=text.length()&&text.substring(n,n+12).matches("(?:§[0-9a-fA-F]){6}")){format+=text.substring(n,n+12);n+=12;state.setLength(0);}
        else if(code=='r'||"0123456789abcdef".indexOf(code)>=0)state.setLength(0);
        out.append(format);state.append(format);continue;
      }
      if(c>=SPACE&&c<SPACE+26){int i=c-SPACE;pending+=(1<<(i%13))*(i>=13?-1:1);continue;}
      if(pending>0)for(int k=0;k<Math.max(1,(pending+2)/4);k++)out.append(' ');pending=0;
      boolean found=false;
      for(Profile p:profiles.values()) {
        boolean bold=p.hasBold()&&c>=p.boldBase&&c<p.boldBase+p.frames();int index=c-(bold?p.boldBase:p.base);
        if(index<0||index>=p.frames())continue;
        if(p.image.isEmpty()) {
          if(bold)out.append("§l");out.append(ALPHABET.charAt(index));
          if(bold)out.append("§r").append(state);
        } else if(p.id.startsWith("tab:image:")) {
          AssetDescriptor a=images.apply(UUID.fromString(p.image));if(a!=null)out.appendCodePoint(a.glyphCode(index));
        }
        found=true;break;
      }
      if(!found)out.appendCodePoint(c);
    }
    if(pending>0)for(int k=0;k<Math.max(1,(pending+2)/4);k++)out.append(' ');
    return out.toString();
  }

  public static final class Encoded {
    public final String text;
    public final int width;

    public Encoded(String text, int width) {
      this.text = text;
      this.width = width;
    }
  }

  public static Encoded encode(Profile p, String legacy) {
    return encode(p,legacy,Collections.emptyMap(),Integer.MAX_VALUE);
  }

  public static Encoded encode(Profile p,String legacy,Map<Integer,Integer> foreign,int maxWidth) {
    if (!p.image.isEmpty()) throw new IllegalArgumentException("Expected a text profile");
    StringBuilder out = new StringBuilder(), state = new StringBuilder();
    int width = 0;
    boolean bold = false;
    for (int n = 0; n < legacy.length(); ) {
      int c = legacy.codePointAt(n);
      n += Character.charCount(c);
      if (c == '§' && n < legacy.length()) {
        char style = Character.toLowerCase(legacy.charAt(n++));
        if (style == 'x' && n + 12 <= legacy.length()) {
          String rgb = legacy.substring(n, n + 12);
          if (rgb.matches("(?:§[0-9a-fA-F]){6}")) {
            bold = false;
            state.setLength(0);
            state.append("§x").append(rgb);
            out.append(state);
            n += 12;
            continue;
          }
        }
        if (style == 'k' || style == 'm' || style == 'n') continue;
        if (style == 'r' || "0123456789abcdef".indexOf(style) >= 0) {
          bold = false;
          state.setLength(0);
        }
        if (style == 'l') { bold = true; if(p.hasBold())continue; }
        out.append('§').append(style);
        state.append('§').append(style);
        continue;
      }
      if (c == ' ') {
        int w = Math.max(2, p.height / 2);
        if(width+w>maxWidth)break;
        out.append("§r").append(shift(w)).append("§r").append(state);
        width += w;
        continue;
      }
      Integer foreignWidth=foreign.get(c);
      if(c>=SPACE&&c<SPACE+26){int index=c-SPACE,amount=(1<<(index%13))*(index>=13?-1:1);if(amount>0&&width+amount>maxWidth)break;out.appendCodePoint(c);width+=amount;continue;}
      if(foreignWidth!=null) { int w=foreignWidth+(bold&&!p.hasBold()?1:0);if(width+w>maxWidth)break;out.appendCodePoint(c);width+=w;continue; }
      int i = ALPHABET.indexOf(c <= 65535 ? (char) c : '?');
      if (i < 0) i = ALPHABET.indexOf('?');
      if(width+p.advance(i,bold)>maxWidth)break;
      out.append(p.glyph(i,bold));
      width += p.advance(i,bold);
    }
    return new Encoded(out.toString(), width);
  }

  /** Each block ends at its original cursor position, so the combined action bar stays centered. */
  public static String position(Encoded e, int x) {
    return "§r" + shift(x) + e.text + "§r" + shift(-x - e.width) + "§r";
  }
}
