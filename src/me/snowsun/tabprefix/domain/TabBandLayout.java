package me.snowsun.tabprefix.domain;
import java.util.*;
/** Rotates whole visual blocks without dropping the reserved rows of bitmap artwork. */
public final class TabBandLayout {
 private TabBandLayout(){}
 public static String fit(List<String> blocks,int rows,long tick,boolean animate){
  if(blocks.isEmpty())return "";List<String> safe=new ArrayList<>();for(String block:blocks){String[] lines=block.split("\n",-1);safe.add(String.join("\n",Arrays.asList(lines).subList(0,Math.min(rows,lines.length))));}blocks=safe;
  int total=0;int[] costs=new int[blocks.size()];for(int n=0;n<costs.length;n++){costs[n]=blocks.get(n).split("\n",-1).length;total+=costs[n];}if(total<=rows)return String.join("\n",blocks);
  int room=rows-costs[0];boolean whole=room<=0;for(int n=1;n<costs.length;n++)if(costs[n]>room)whole=true;
  List<String> out=new ArrayList<>();int used=0;if(!whole||!animate){out.add(blocks.get(0));used=costs[0];}
  int offset=animate?(int)((tick/100)%(whole?blocks.size():Math.max(1,blocks.size()-1))):0,count=whole?blocks.size():blocks.size()-1;
  for(int n=0;n<count;n++){int i=whole?(offset+n)%blocks.size():1+(offset+n)%(blocks.size()-1);if(!animate&&i==0)continue;if(used+costs[i]>rows)continue;out.add(blocks.get(i));used+=costs[i];}return String.join("\n",out);
 }
}
