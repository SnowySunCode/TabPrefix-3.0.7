package me.snowsun.tabprefix.domain;
import java.util.*;
/** GUI pixel metrics for vanilla text and delivered private glyphs. */
public final class TabMetrics {
 private TabMetrics(){}
 public static int width(String text,Map<Integer,Integer> images){
  int width=0;boolean bold=false;
  for(int n=0;n<text.length();){int c=text.codePointAt(n);n+=Character.charCount(c);
   if(c=='§'&&n<text.length()){char code=Character.toLowerCase(text.charAt(n++));if(code=='x'&&n+12<=text.length()&&text.substring(n,n+12).matches("(?:§[0-9a-fA-F]){6}")){n+=12;bold=false;continue;}if(code=='r'||"0123456789abcdef".indexOf(code)>=0)bold=false;else if(code=='l')bold=true;continue;}
   boolean shift=c>=NativeHudLayout.SPACE&&c<NativeHudLayout.SPACE+26;Integer metric=images.get(c);
   int advance=metric!=null?metric:shift?(1<<((c-NativeHudLayout.SPACE)%13))*(c-NativeHudLayout.SPACE>=13?-1:1):c==' '?4:"!.,:;i|".indexOf(c)>=0?2:"'`l".indexOf(c)>=0?3:"I[]t".indexOf(c)>=0?4:"fk{}()".indexOf(c)>=0?5:c>65535?16:c>127?10:6;
   width+=advance+(bold&&c!=' '&&!shift?1:0);
  }return width;
 }
 public static int above(AssetDescriptor a){return Math.max(0,(a.ascent-7+8)/9);}
 public static int below(AssetDescriptor a){return Math.max(0,(a.renderHeight-a.ascent-1+8)/9);}
 public static int contentWidth(int total,int columns,int gap){return Math.max(4,(total-5*(columns-1))/columns-22-Math.min(8,gap));}
 public static int[] compactBoxWidths(DisplayDesign d,DisplayArtwork.Box box){TreeSet<Integer> out=new TreeSet<>();int max=contentWidth(d.tabWidth,1,d.tabColumnGap);for(int w=16;w<max;w+=16)out.add(w);out.add(max);out.add(Math.min(max,box.width));for(int c=2;c<=4;c++)out.add(contentWidth(d.tabWidth,c,d.tabColumnGap));return out.stream().mapToInt(Integer::intValue).toArray();}
 public static String fit(String text,Map<Integer,Integer> metrics,int limit,long tick,boolean animate){return fit(text,metrics,limit,tick,animate,"..");}
 public static String fit(String text,Map<Integer,Integer> metrics,int limit,long tick,boolean animate,String ellipsis){
  if(width(text,metrics)<=limit)return text;
  List<String> tokens=new ArrayList<>();List<Integer> widths=new ArrayList<>();StringBuilder state=new StringBuilder();
  for(int n=0;n<text.length();){int c=text.codePointAt(n);n+=Character.charCount(c);
   if(c=='§'&&n<text.length()){char code=Character.toLowerCase(text.charAt(n++));if(code=='x'&&n+12<=text.length()&&text.substring(n,n+12).matches("(?:§[0-9a-fA-F]){6}")){state.setLength(0);state.append("§x").append(text,n,n+12);n+=12;}else{if(code=='r'||"0123456789abcdef".indexOf(code)>=0)state.setLength(0);state.append('§').append(code);}continue;}
   String token="§r"+state+new String(Character.toChars(c));int w=width(token,metrics);if(w>0){tokens.add(token);widths.add(w);}
  }
  if(tokens.isEmpty())return "";int start=animate?(int)((tick/6)%tokens.size()):0,used=0,budget=animate?limit:Math.max(0,limit-width(ellipsis,metrics));StringBuilder out=new StringBuilder();
  for(int n=start;n<tokens.size();n++){int w=widths.get(n);if(w>budget)continue;if(used+w>budget)break;out.append(tokens.get(n));used+=w;}
  if(!animate&&width(ellipsis,metrics)<=limit)out.append("§r").append(ellipsis);return out.toString();
 }
 public static NativeHudLayout.Encoded compactText(NativeHudLayout.Profile font,String text,Map<Integer,Integer> metrics){
  Map<Integer,Integer> foreign=metrics;
  for(int n=0;n<text.length();){int c=text.codePointAt(n);n+=Character.charCount(c);boolean unsupported=c>127&&c!='§'&&!metrics.containsKey(c)&&!(c>=NativeHudLayout.SPACE&&c<NativeHudLayout.SPACE+26)&&(c>65535||NativeHudLayout.ALPHABET.indexOf((char)c)<0);if(unsupported){if(foreign==metrics)foreign=new HashMap<>(metrics);foreign.put(c,width(new String(Character.toChars(c)),Collections.emptyMap()));}}
  return NativeHudLayout.encode(font,text,foreign,Integer.MAX_VALUE);
 }
}
