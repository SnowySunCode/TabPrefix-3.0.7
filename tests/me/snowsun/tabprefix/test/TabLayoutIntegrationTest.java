package me.snowsun.tabprefix.test;
import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.presentation.display.CompactTabLayout;
/** Real PNG advances and legal vanilla packet geometry, independent of the web preview. */
public final class TabLayoutIntegrationTest {
 static int checks;static void check(boolean v,String why){if(!v)throw new AssertionError(why);checks++;}
 static JsonObject box(String surface,int width,int height){JsonObject j=new JsonObject();j.addProperty("surface",surface);j.addProperty("index",0);j.addProperty("width",width);j.addProperty("height",height);j.addProperty("padding",4);return j;}
 public static void main(String[] args)throws Exception{
  Path project=Paths.get(args[0]),root=Files.createTempDirectory(project.resolve(".build/test-data"),"compact-");PluginSettings cfg=HdVersionsIntegrationTest.settings(root,MinecraftVersion.parse("1.21.11"));
  JsonObject doc=DisplayDesign.defaults(false).json();doc.addProperty("tabWidth",280);doc.addProperty("layoutEnabled",true);doc.addProperty("headerEnabled",true);doc.addProperty("footerEnabled",true);JsonArray boxes=new JsonArray();boxes.add(box("HEADER",400,64));boxes.add(box("FOOTER",280,26));boxes.add(box("CELL",120,8));doc.getAsJsonObject("artwork").add("boxes",boxes);
  UUID id=UUID.randomUUID();Path directory=cfg.dataDirectory.resolve("assets").resolve(id.toString());Files.createDirectories(directory);
  for(int f=0;f<2;f++){BufferedImage image=new BufferedImage(256,64,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<64;y++)for(int x=0;x<(f==0?256:120);x++)image.setRGB(x,y,0xff80cbb0);ImageIO.write(image,"png",directory.resolve(String.format("frame-%04d.png",f)).toFile());}
  AssetDescriptor a=new AssetDescriptor(id,"gif",256,64,64,60,new int[]{2,3},new int[]{0xe000,0xe001},1);JsonObject slot=doc.getAsJsonArray("slots").get(0).getAsJsonObject();slot.addProperty("kind","IMAGE");slot.addProperty("image",id.toString());DisplayDesign design=new DisplayDesign(doc);
  check(design.tabLayoutMode.equals("COMPACT")&&design.slots.size()==80,"old documents migrate without losing authored cells");check(new DisplayDesign(design.json()).json().equals(design.json()),"new settings round-trip");
  PackBuilder builder=new PackBuilder(cfg,new MediaStore(cfg),cfg.minecraft,"http://127.0.0.1:8999");builder.designs(()->design);PackRevision pack=builder.build(new GraphicSnapshot(Collections.singletonMap(id,a),Collections.emptyMap(),Collections.emptyMap()));NativeHudLayout hud=pack.hud;NativeHudLayout.Profile font=hud.get("tab:compact",0,7,""),icon=hud.get("tab:image:"+id,0,8,id.toString());
  check(font!=null&&icon!=null,"compact text and image profiles are delivered");check(icon.advance(0)==icon.advance(1),"transparent GIF frames reserve stable width");check(icon.advance(0)<=30,"wide GIF scales proportionally into the narrowest column");check(TabMetrics.compactText(font,"汉字 Ω 😀",hud.metrics).text.contains("汉字"),"Unicode outside the private alphabet survives");check(TabMetrics.compactText(font,"汉字 Ω 😀",hud.metrics).text.contains("😀"),"supplementary Unicode survives");
  JsonObject proof=new JsonObject();proof.addProperty("pack",pack.file.toString());JsonArray samples=new JsonArray();proof.add("cases",samples);
  try(ZipFile zip=new ZipFile(pack.file.toFile())){
   BufferedImage ping=ImageIO.read(zip.getInputStream(zip.getEntry("assets/minecraft/textures/gui/sprites/icon/ping_unknown.png")));for(int y=0;y<8;y++)for(int x=0;x<10;x++)check((ping.getRGB(x,y)>>>24)==0,"decoration has no fake green ping icon");Map<Integer,Integer> metrics=ArtworkIntegrationTest.metrics(zip);
   for(NativeHudLayout.Profile p:hud.profiles.values())for(int n=0;n<p.frames();n++)check(metrics.get(p.base+n)==p.advance(n),"metadata agrees with independent PNG advances");
   for(int width:new int[]{180,260,280,420})for(int count:new int[]{1,2,8,19,20,21,40,41,60,61,79,80}){
    List<VirtualTabPackets.Cell> cells=new ArrayList<>(Collections.nCopies(80,new VirtualTabPackets.Cell("",null)));for(int n=0;n<count;n++)cells.set(n,new VirtualTabPackets.Cell(TabMetrics.compactText(font,n==0?icon.glyph(0)+" §a[ADMIN] §fAle9andro §7[Creative Builder]":"§7Player_"+(n+1)+" §8· §aVIP",hud.metrics).text,null));
    CompactTabLayout.Result result=CompactTabLayout.arrange(cells,new int[80],width,6,hud.metrics,true,0,true);int columns=1;while((result.cells.size()+columns-1)/columns>20)columns++;check(result.columns==columns&&result.rows==(result.cells.size()+columns-1)/columns,"actual packet count produces advertised grid");check(result.rows<=20&&result.cells.size()<=80,"protocol capacity");Set<Integer> seen=new HashSet<>();int widest=0;
    for(int n=0;n<result.cells.size();n++){if(result.source[n]>=0)check(seen.add(result.source[n]),"active block appears once");String text=result.cells.get(n).text;if(n==0&&width==280)text=ArtworkText.compact(hud,design,"CELL",0,text,result.contentWidth,0,true);widest=Math.max(widest,ArtworkIntegrationTest.width(text,metrics));}
    check(seen.size()==count,"no active block is lost");check((widest+22)*result.columns+5*(result.columns-1)<=width,"full table including heads and ping gutters stays within width");
    if(width==280&&(count==1||count==21||count==80)){JsonObject sample=new JsonObject();sample.addProperty("count",count);sample.addProperty("columns",result.columns);sample.addProperty("rows",result.rows);sample.addProperty("cellWidth",widest+22);JsonArray lines=new JsonArray();for(int n=0;n<result.cells.size();n++)lines.add(n==0?ArtworkText.compact(hud,design,"CELL",0,result.cells.get(n).text,result.contentWidth,0,true):result.cells.get(n).text);sample.add("cells",lines);samples.add(sample);}
   }
   for(String surface:new String[]{"HEADER","FOOTER"}){String text=ArtworkText.compact(hud,design,surface,0,"§aMinecraft §fLong server title · online and community",280,0,false);check(text.split("\n",-1).length<=2,"large banners respect vertical budget");check(ArtworkIntegrationTest.width(text.split("\n",-1)[0],metrics)<=280,"banner and title fit shared width");proof.addProperty(surface.toLowerCase(),text);}
  }
  Set<String> seen=new HashSet<>();for(long tick=0;tick<400;tick+=100){String text=TabBandLayout.fit(Arrays.asList("Title","Online","World","Clock"),2,tick,true);check(text.startsWith("Title\n")&&text.split("\n",-1).length==2,"title stays pinned while metadata rotates");seen.add(text.split("\n")[1]);check(TabBandLayout.fit(Arrays.asList("First\n ","Second\n "),3,tick,true).split("\n",-1).length<=3,"whole bitmap blocks retain reserved rows");}check(seen.containsAll(Arrays.asList("Online","World","Clock")),"all surplus information remains reachable");check(TabBandLayout.fit(Arrays.asList("One","Two"),2,100,true).equals("One\nTwo"),"fitting bands do not rotate");
  Files.write(project.resolve(".build/tab-layout-proof.json"),proof.toString().getBytes(StandardCharsets.UTF_8));System.out.println("PASS: "+checks+" compact TAB checks: actual packet geometry, 1..80 blocks, four widths, independent PNG metrics, GIF stability and bounded banners.");
 }
}
