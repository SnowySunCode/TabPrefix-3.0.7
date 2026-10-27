package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.domain.*;

/** Moves full Unicode code points, carrying colour and decoration without splitting RGB codes. */
public final class DisplayAnimation {
  private DisplayAnimation() {}

  /** Style-carrying cells are shared by pixel fitting and text effects. */
  public static List<String> cells(String legacy){
    List<String> out=new ArrayList<>();String color="",decorations="";
    for(int n=0;n<legacy.length();){if(legacy.charAt(n)=='§'&&n+1<legacy.length()){char code=Character.toLowerCase(legacy.charAt(n+1));if(code=='x'&&n+14<=legacy.length()&&legacy.substring(n,n+14).matches("§[xX](§[0-9a-fA-F]){6}")){color=legacy.substring(n,n+14);decorations="";n+=14;}else{if(code=='r'){color="";decorations="";}else if("0123456789abcdef".indexOf(code)>=0){color=legacy.substring(n,n+2);decorations="";}else if("klmno".indexOf(code)>=0)decorations+=legacy.substring(n,n+2);n+=2;}}else{int cp=legacy.codePointAt(n);out.add("§r"+color+decorations+new String(Character.toChars(cp)));n+=Character.charCount(cp);}}
    return out;
  }
  private static String join(List<String> cells,int start,int length){StringBuilder out=new StringBuilder();for(int n=start;n<Math.min(cells.size(),start+length);n++)out.append(cells.get(n));return out.append("§r").toString();}
  public static String animate(String legacy,DisplayDesign.Line line,long tick){
    long step=tick/line.speed;if(line.animation.equals("SCROLL"))return scroll(legacy,line.width,line.gap,step);
    if(line.animation.equals("NONE")||line.animation.equals("FRAMES"))return legacy;
    List<String> cells=cells(legacy);if(cells.isEmpty())return legacy;
    switch(line.animation){
      case "BOUNCE":{int distance=Math.max(0,cells.size()-line.width);if(distance==0)return legacy;int offset=(int)Math.floorMod(step,(long)distance*2);return join(cells,offset<=distance?offset:distance*2-offset,line.width);}
      case "TYPEWRITER":{int phase=(int)Math.floorMod(step,(long)(cells.size()+1)*2+line.gap);int count=Math.min(cells.size(),phase<=cells.size()+line.gap?phase:Math.max(0,(cells.size()+1)*2+line.gap-phase));return join(cells,0,count);}
      case "BLINK":return Math.floorMod(step,12)<8?legacy:join(Collections.nCopies(cells.size()," "),0,cells.size());
      default:{StringBuilder out=new StringBuilder();String palette=line.animation.equals("WAVE")?"c6eab9d":line.animation.equals("PULSE")?"878f7873": "333bbffbb333";for(int n=0;n<cells.size();n++){String cell=cells.get(n);int cp=cell.codePointBefore(cell.length());String glyph=new String(Character.toChars(cp));if(cp>=0xe000&&cp<=0xf8ff||cp>=NativeHudLayout.FIRST)out.append("§f").append(glyph);else out.append('§').append(palette.charAt((int)Math.floorMod(step+(line.animation.equals("PULSE")?0:n),(long)palette.length()))).append(glyph);}return out.append("§r").toString();}
    }
  }

  /** A narrow sidebar scrolls complete glyphs instead of silently dropping the tail. */
  public static NativeHudLayout.Encoded fit(NativeHudLayout.Profile font,String legacy,Map<Integer,Integer> foreign,int width,String mode,long tick,boolean animate){
    NativeHudLayout.Encoded full=NativeHudLayout.encode(font,legacy,foreign,Integer.MAX_VALUE);if(full.width<=width||mode.equals("SHOW"))return full;
    List<String> cells=cells(legacy);if(mode.equals("SCROLL")&&animate){for(int n=0;n<4;n++)cells.add(" ");int start=(int)Math.floorMod(tick/6,(long)cells.size());StringBuilder text=new StringBuilder();int used=0;for(int n=0;n<cells.size();n++){String cell=cells.get((start+n)%cells.size());int advance=NativeHudLayout.encode(font,cell,foreign,Integer.MAX_VALUE).width;if(used+advance>width&&used>0)break;text.append(cell);used+=advance;if(used>=width)break;}return NativeHudLayout.encode(font,text.toString(),foreign,Integer.MAX_VALUE);}
    NativeHudLayout.Encoded dots=NativeHudLayout.encode(font,"§r…",foreign,Integer.MAX_VALUE);int tail=dots.width;NativeHudLayout.Encoded text=NativeHudLayout.encode(font,legacy,foreign,Math.max(0,width-tail));return new NativeHudLayout.Encoded(text.text+dots.text,text.width+tail);
  }

  public static String scroll(String legacy, int width, int gap, long step) {
    List<String> glyphs = new ArrayList<>(), styles = new ArrayList<>();
    String color = "", decorations = "";
    for (int i = 0; i < legacy.length(); ) {
      if (legacy.charAt(i) == '§' && i + 1 < legacy.length()) {
        char code = Character.toLowerCase(legacy.charAt(i + 1));
        if (code == 'x'
            && i + 14 <= legacy.length()
            && legacy.substring(i, i + 14).matches("§[xX](§[0-9a-fA-F]){6}")) {
          color = legacy.substring(i, i + 14);
          decorations = "";
          i += 14;
        } else {
          if (code == 'r') {
            color = "";
            decorations = "";
          } else if ("0123456789abcdef".indexOf(code) >= 0) {
            color = legacy.substring(i, i + 2);
            decorations = "";
          } else if ("klmno".indexOf(code) >= 0) decorations += legacy.substring(i, i + 2);
          i += 2;
        }
      } else {
        int cp = legacy.codePointAt(i);
        glyphs.add(new String(Character.toChars(cp)));
        styles.add(color + decorations);
        i += Character.charCount(cp);
      }
    }
    if (glyphs.isEmpty()) return legacy;
    int padding = Math.max(gap, width - glyphs.size() + gap);
    for (int i = 0; i < padding; i++) {
      glyphs.add(" ");
      styles.add("");
    }
    int offset = (int) Math.floorMod(step, (long) glyphs.size());
    StringBuilder result = new StringBuilder();
    String previous = null;
    for (int i = 0; i < width; i++) {
      int index = (offset + i) % glyphs.size();
      String style = styles.get(index);
      if (!style.equals(previous)) {
        result.append("§r").append(style);
        previous = style;
      }
      result.append(glyphs.get(index));
    }
    return result.append("§r").toString();
  }
}
