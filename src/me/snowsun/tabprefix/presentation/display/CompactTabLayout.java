package me.snowsun.tabprefix.presentation.display;
import java.util.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
/** Reflows authored cells into the smallest legal vanilla table. */
public final class CompactTabLayout {
 private CompactTabLayout(){}
 public static final class Result{public final List<VirtualTabPackets.Cell> cells;public final int[] source;public final int columns,rows,contentWidth;Result(List<VirtualTabPackets.Cell> cells,int[] source,int columns,int rows,int width){this.cells=cells;this.source=source;this.columns=columns;this.rows=rows;this.contentWidth=width;}}
 public static Result arrange(List<VirtualTabPackets.Cell> cells,int[] insets,int width,int gap,Map<Integer,Integer> metrics,boolean pixelSpaces,long tick,boolean animate){return arrange(cells,insets,width,gap,metrics,pixelSpaces,tick,animate,"..");}
 public static Result arrange(List<VirtualTabPackets.Cell> cells,int[] insets,int width,int gap,Map<Integer,Integer> metrics,boolean pixelSpaces,long tick,boolean animate,String ellipsis){
  if(cells.size()!=80||insets.length!=80)throw new IllegalArgumentException("80 authored cells required");List<Integer> active=new ArrayList<>();for(int n=0;n<80;n++)if(!cells.get(n).text.isEmpty()||cells.get(n).player!=null)active.add(n);
  int columns=Math.max(1,(active.size()+19)/20),rows=Math.max(1,(active.size()+columns-1)/columns),count=columns*rows,limit=TabMetrics.contentWidth(width,columns,gap),widest=0;int[] source=new int[count];Arrays.fill(source,-1);List<VirtualTabPackets.Cell> out=new ArrayList<>();
  for(int n=0;n<count;n++){if(n>=active.size()){out.add(new VirtualTabPackets.Cell("",null));continue;}int i=active.get(n);source[n]=i;VirtualTabPackets.Cell cell=cells.get(i);String text=TabMetrics.fit(cell.text,metrics,Math.max(1,limit-insets[i]),tick,animate,ellipsis);widest=Math.max(widest,TabMetrics.width(text,metrics));out.add(new VirtualTabPackets.Cell(text,cell.player));}
  for(int n=0;n<count;n++){if(source[n]<0||insets[source[n]]>0)continue;VirtualTabPackets.Cell cell=out.get(n);int padding=widest-TabMetrics.width(cell.text,metrics)+Math.min(8,gap);String end=pixelSpaces?NativeHudLayout.shift(padding):spaces(padding/4);out.set(n,new VirtualTabPackets.Cell(cell.text+"§r"+end,cell.player));}
  return new Result(out,source,columns,rows,limit);
 }
 private static String spaces(int n){StringBuilder out=new StringBuilder();while(n-->0)out.append(' ');return out.toString();}
}
