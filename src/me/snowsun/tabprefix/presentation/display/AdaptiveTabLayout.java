package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;

/** Keeps 80 protocol slots while inserting room around large decorations. Never drops a cell. */
public final class AdaptiveTabLayout {
  private AdaptiveTabLayout(){}
  public static final class Result {
    public final List<VirtualTabPackets.Cell> cells;public final int[] source, widths;public final boolean fitted;
    Result(List<VirtualTabPackets.Cell> cells,int[] source,int[] widths,boolean fitted){this.cells=cells;this.source=source;this.widths=widths;this.fitted=fitted;}
  }
  public static Result arrange(List<VirtualTabPackets.Cell> original,int[] above,int[] below,int[] stableWidths,int gap,Map<Integer,Integer> images,boolean pixelSpaces){
    if(original.size()!=80||above.length!=80||below.length!=80||stableWidths.length!=80)throw new IllegalArgumentException("80 cells required");
    List<VirtualTabPackets.Cell> out=new ArrayList<>(Collections.nCopies(80,new VirtualTabPackets.Cell("",null)));int[] source=new int[80],widths=new int[4];Arrays.fill(source,-1);boolean fitted=true;
    for(int col=0;col<4;col++){int cursor=0;for(int row=0;row<20;row++){int i=col*20+row;VirtualTabPackets.Cell cell=original.get(i);if(cell.text.isEmpty()&&cell.player==null)continue;int position=Math.max(row,cursor)+above[i];if(position+below[i]>=20){fitted=false;break;}int target=col*20+position;out.set(target,cell);source[target]=i;cursor=position+1+below[i];widths[col]=Math.max(widths[col],Math.max(stableWidths[i],TabMetrics.width(cell.text,images)));}if(!fitted)break;}
    if(!fitted){out=new ArrayList<>(original);for(int i=0;i<80;i++){source[i]=i;widths[i/20]=Math.max(widths[i/20],Math.max(stableWidths[i],TabMetrics.width(original.get(i).text,images)));}}
    for(int i=0;i<80;i++){VirtualTabPackets.Cell cell=out.get(i);if(cell.text.isEmpty()&&cell.player==null)continue;int padding=Math.max(0,widths[i/20]-TabMetrics.width(cell.text,images))+gap;String end=pixelSpaces?NativeHudLayout.shift(padding):spaces((padding+3)/4);out.set(i,new VirtualTabPackets.Cell(cell.text+"§r"+end,cell.player));}
    return new Result(out,source,widths,fitted);
  }
  private static String spaces(int count){StringBuilder s=new StringBuilder();for(int n=0;n<count;n++)s.append(' ');return s.toString();}
}
