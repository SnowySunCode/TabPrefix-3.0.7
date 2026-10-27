package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.presentation.display.*;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;

/** Actual calendar boundaries, shared random trials and living-entity lifecycle. */
public final class PolishIntegrationTest {
  private static int checks;
  private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
  private static void reject(Runnable r){try{r.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException e){checks++;}}
  private static JsonObject obj(String json){return new JsonParser().parse(json).getAsJsonObject();}
  private static long at(String value){return ZonedDateTime.parse(value).toInstant().toEpochMilli();}
  private static Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==double.class)return 0d;if(t==float.class)return 0f;if(t==long.class)return 0L;return null;}
  @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler h){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h);}

  public static void main(String[] args) throws Exception {calendar();random();bindings();groups();prefixes(args[0]);System.out.println("PASS: "+checks+" polish checks: calendar, shared random draws, real boss health/lifecycle and group rules.");}
  private static void calendar(){
    BossSchedule s=new BossSchedule(obj("{mode:'CALENDAR',timezone:'Europe/Moscow',weekDays:[1,2,3,4,5],months:[10],monthDays:[12],startTime:'18:00',endTime:'22:00'}"));
    check(s.calendar(at("2026-10-12T18:00:00+03:00")),"inclusive start");
    check(!s.calendar(at("2026-10-12T17:59:59+03:00")),"before window");
    check(!s.calendar(at("2026-10-12T22:00:00+03:00")),"exclusive end");
    check(!s.calendar(at("2026-10-13T19:00:00+03:00")),"month day filter");
    check(!s.calendar(at("2026-11-12T19:00:00+03:00")),"month filter");
    check(!s.calendar(at("2026-10-17T19:00:00+03:00")),"weekday filter");
    s=new BossSchedule(obj("{mode:'CALENDAR',timezone:'UTC',startTime:'22:30',endTime:'02:15'}"));
    check(s.calendar(at("2026-10-12T23:59:00Z"))&&s.calendar(at("2026-10-13T01:30:00Z")),"overnight");
    check(!s.calendar(at("2026-10-13T02:15:00Z")),"overnight end");
    s=new BossSchedule(obj("{mode:'CALENDAR',timezone:'UTC',weekDays:[1],startTime:'22:30',endTime:'02:15'}"));
    check(s.calendar(at("2026-10-13T01:30:00Z")),"Monday overnight continues on Tuesday morning");
    check(!s.calendar(at("2026-10-14T01:30:00Z")),"Tuesday night is not Monday's occurrence");
    s=new BossSchedule(obj("{mode:'CALENDAR',timezone:'UTC',hours:[12],minutes:[30]}"));
    check(s.calendar(at("2026-10-12T12:30:59Z"))&&!s.calendar(at("2026-10-12T12:31:00Z")),"minute filter");
    check(s.json().toString().equals(new BossSchedule(s.json()).json().toString()),"schedule restart serialization");
    for(String json:new String[]{"{timezone:'Unknown/zone'}","{hours:[24]}","{minutes:[-1]}","{weekDays:['1']}","{months:[true]}","{startTime:'25:00',endTime:'23:00'}","{startTime:'10:00'}","{chance:101}","{intervalMinutes:1.5}","{mode:'RANDOM',durationSeconds:61,intervalMinutes:1}"})reject(()->new BossSchedule(obj(json)));
  }
  private static void random(){
    long now=at("2026-10-12T12:00:00Z");
    BossSchedule all=new BossSchedule(obj("{mode:'RANDOM',timezone:'UTC',chance:100,intervalMinutes:10,durationSeconds:60}"));
    check(all.reason("boss",now,17).isEmpty(),"100% appears at interval start");
    check(all.reason("boss",now+59000,17).isEmpty(),"duration active");
    check(all.reason("boss",now+60000,17).equals("RANDOM_WAIT"),"duration ends");
    BossSchedule none=new BossSchedule(obj("{mode:'RANDOM',timezone:'UTC',chance:0,intervalMinutes:10,durationSeconds:60}"));
    check(none.reason("boss",now,17).equals("RANDOM_MISS"),"0% never appears");
    BossSchedule weighted=new BossSchedule(obj("{mode:'RANDOM',timezone:'UTC',chance:25,intervalMinutes:1,durationSeconds:60,rules:[{weekDays:[1],hours:[12],chance:80},{weekDays:[1],chance:10}]}"));
    check(weighted.probability(now)==80&&weighted.probability(now+3600000)==10&&weighted.probability(now+86400000)==25,"weighted first match and default");
    int successes=0;for(int n=0;n<5000;n++){long t=now+n*60000L;String result=weighted.reason("boss",t,987);
      check(result.equals(weighted.reason("boss",t+1000,987)),"all viewers and ticks share one draw");
      if(new BossSchedule(obj("{mode:'RANDOM',timezone:'UTC',chance:25,intervalMinutes:1,durationSeconds:60}")).reason("boss",t,987).isEmpty())successes++;
    }
    check(successes>1050&&successes<1450,"random probability does not multiply with viewer count");
  }
  private static final class Mob {
    final UUID id=UUID.randomUUID();World world;EntityType type=EntityType.WITHER;
    double x=8,health=150,max=300;boolean dead,valid=true;String name="Raid boss";final Set<String> tags=new HashSet<>(Arrays.asList("raid"));
    final LivingEntity api=proxy(LivingEntity.class,(p,m,a)->{
      switch(m.getName()){
        case"getUniqueId":return id;case"getWorld":return world;case"getType":return type;
        case"getLocation":return new Location(world,x,64,0);case"getHealth":return health;case"getMaxHealth":return max;
        case"getCustomName":return name;case"getScoreboardTags":return tags;case"isDead":return dead;case"isValid":return valid;
        case"hashCode":return System.identityHashCode(p);case"equals":return p==a[0];default:return zero(m.getReturnType());
      }
    });
  }
  private static void bindings(){
    List<LivingEntity> mobs=new ArrayList<>();int[] scans={0};
    World world=proxy(World.class,(p,m,a)->{switch(m.getName()){case"getName":return"world";case"getLivingEntities":scans[0]++;return mobs;case"hashCode":return System.identityHashCode(p);case"equals":return p==a[0];default:return zero(m.getReturnType());}});
    Player viewer=proxy(Player.class,(p,m,a)->{switch(m.getName()){case"getUniqueId":return new UUID(0,11);case"getWorld":return world;case"getLocation":return new Location(world,0,64,0);case"hasPermission":return true;case"hashCode":return System.identityHashCode(p);case"equals":return p==a[0];default:return zero(m.getReturnType());}});
    Mob mob=new Mob();mob.world=world;mobs.add(mob.api);BossEntityResolver resolver=new BossEntityResolver();
    for(String json:new String[]{"{mode:'TYPE',entityType:'WITHER'}","{mode:'NAME',name:'Raid boss'}","{mode:'TAG',tag:'raid'}","{mode:'UUID',uuid:'"+mob.id+"'}","{mode:'NEAREST'}"})check(resolver.find(viewer,new BossBinding(obj(json)),1)==mob.api,"loaded mob selection");
    check(scans[0]==1,"one world scan per tick for every bar and viewer");
    check(resolver.find(viewer,new BossBinding(obj("{mode:'TYPE',entityType:'ZOMBIE'}")),1)==null,"type filter");
    check(resolver.find(viewer,new BossBinding(obj("{mode:'NEAREST',radius:4}")),1)==null,"radius filter");
    for(String json:new String[]{"{mode:'UUID',uuid:'1-2-3-4-5'}","{mode:'NAME'}","{mode:'TAG'}","{radius:0}"})reject(()->new BossBinding(obj(json)));
    JsonObject doc=DisplayDesign.defaults(false).json();doc.addProperty("bossBarsEnabled",true);JsonArray bars=new JsonArray();JsonObject b=obj("{id:'bound',progressMode:'DRAIN',durationTicks:20,loop:false,hideAfter:true,line:{text:'{boss_name} {boss_health}/{boss_max_health}'},binding:{mode:'TAG',tag:'raid'}}");bars.add(b);doc.add("bossBars",bars);DisplayDesign design=new DisplayDesign(doc);
    List<BossBarIntegrationTest.NativeBar> nativeBars=new ArrayList<>();
    Server server=proxy(Server.class,(p,m,a)->{if(m.getName().equals("createBossBar")){BossBarIntegrationTest.NativeBar bar=new BossBarIntegrationTest.NativeBar((String)a[0],(BarColor)a[1],(BarStyle)a[2]);nativeBars.add(bar);return bar.api;}return zero(m.getReturnType());});
    BossBarView view=new BossBarView(server);Map<String,String> vars=new HashMap<>();
    java.util.function.BiFunction<DisplayDesign.Line,Map<String,String>,String> text=(line,v)->{String out=line.text;for(Map.Entry<String,String> entry:v.entrySet())out=out.replace("{"+entry.getKey()+"}",entry.getValue());return out;};
    PlayerDisplaySettings prefs=new PlayerDisplaySettings(new JsonObject());view.render(viewer,design,prefs,vars,10,1,text);
    check(view.count()==1&&nativeBars.get(0).progress==.5,"native bar follows real entity health");
    check(!design.bossBars.get(0).timed(),"binding overrides an old timer preset");
    check(nativeBars.get(0).title.equals("Raid boss 150/300"),"boss placeholders");
    mob.health=30;view.render(viewer,design,prefs,vars,11,1,text);check(nativeBars.size()==1&&nativeBars.get(0).progress==.1,"health changes reuse native bar");
    mob.dead=true;view.render(viewer,design,prefs,vars,12,1,text);check(view.count()==0&&nativeBars.get(0).players.isEmpty(),"death detaches native bar");
    mob.dead=false;mob.valid=false;check(resolver.find(viewer,new BossBinding(obj("{mode:'NEAREST'}")),13)==null,"unloaded entity ignored");
    mob.valid=true;mob.x=70;view.render(viewer,design,prefs,vars,14,1,text);check(view.count()==0,"out of range hides bound bar");view.close();
  }
  private static void prefixes(String project) throws Exception {
    java.nio.file.Path root=java.nio.file.Paths.get(project),data=java.nio.file.Files.createTempDirectory(root.resolve(".build/test-data"),"polish-prefix-");
    me.snowsun.tabprefix.config.PluginSettings settings=new me.snowsun.tabprefix.config.PluginSettings(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(root.resolve("resources/config.yml").toFile()),data);
    Server server=proxy(Server.class,(p,m,a)->{switch(m.getName()){case"getLogger":return java.util.logging.Logger.getLogger("Polish");case"getOnlinePlayers":return Collections.emptyList();case"getBukkitVersion":return"1.16.5";default:return zero(m.getReturnType());}});
    Class<?> pluginClass=Class.forName("me.snowsun.tabprefix.test.DisplayIntegrationTest$TestPlugin");Constructor<?> ctor=pluginClass.getDeclaredConstructor(Server.class,java.nio.file.Path.class);ctor.setAccessible(true);
    org.bukkit.plugin.java.JavaPlugin plugin=(org.bukkit.plugin.java.JavaPlugin)ctor.newInstance(server,data);
    me.snowsun.tabprefix.application.port.GroupPrefixRepository textsRepo=proxy(me.snowsun.tabprefix.application.port.GroupPrefixRepository.class,(p,m,a)->java.util.concurrent.CompletableFuture.completedFuture(m.getName().equals("findAll")?Collections.singletonList(new GroupTextPrefix("vip","&c[OLD]",TextFormat.LEGACY,null,1)):null));
    me.snowsun.tabprefix.application.PrefixService texts=new me.snowsun.tabprefix.application.PrefixService(textsRepo);texts.initialize().get();
    me.snowsun.tabprefix.application.port.GraphicRepository graphicsRepo=proxy(me.snowsun.tabprefix.application.port.GraphicRepository.class,(p,m,a)->java.util.concurrent.CompletableFuture.completedFuture(new GraphicSnapshot(Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap())));
    me.snowsun.tabprefix.application.GraphicService graphics=new me.snowsun.tabprefix.application.GraphicService(graphicsRepo,texts,settings.features);graphics.initialize().get();
    me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge luck=new me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge(plugin,null);luck.localGroups(()->Arrays.asList("vip","default"));
    World world=proxy(World.class,(p,m,a)->m.getName().equals("getName")?"world":zero(m.getReturnType()));UUID id=new UUID(0,92);
    Player player=proxy(Player.class,(p,m,a)->{switch(m.getName()){case"getUniqueId":return id;case"getName":case"getPlayerListName":return"Alex";case"getWorld":return world;case"hasPermission":return true;default:return zero(m.getReturnType());}});
    PrefixController prefixes=new PrefixController(plugin,texts,graphics,luck,null,settings,new TabListView(settings.minecraft));prefixes.managedDisplays();
    java.util.concurrent.atomic.AtomicReference<DisplayDesign> design=new java.util.concurrent.atomic.AtomicReference<>(DisplayDesign.defaults(false));prefixes.displayDesign(design::get);
    Method refresh=PrefixController.class.getDeclaredMethod("refresh",Player.class);refresh.setAccessible(true);refresh.invoke(prefixes,player);
    check(prefixes.textPrefix(id).contains("OLD"),"inherit keeps existing stored group prefix");
    JsonObject doc=design.get().json();JsonArray rules=new JsonArray();rules.add(obj("{group:'elite',groups:['vip'],prefixMode:'CUSTOM',prefix:{text:'<green>[NEW {player}]</green>'}}"));doc.add("groupStyles",rules);design.set(new DisplayDesign(doc));refresh.invoke(prefixes,player);
    check(prefixes.prefix(player,player).contains("NEW Alex")&&prefixes.textPrefix(id).contains("NEW Alex"),"real cached controller applies shared group override and placeholders");
    rules.get(0).getAsJsonObject().addProperty("prefixMode","NONE");design.set(new DisplayDesign(doc));refresh.invoke(prefixes,player);check(prefixes.prefix(player,player).isEmpty(),"NONE removes group prefix without altering LuckPerms");
    rules.get(0).getAsJsonObject().addProperty("prefixMode","CUSTOM");rules.get(0).getAsJsonObject().add("prefix",obj("{animation:'FRAMES',speed:2,frames:['ONE','TWO']}"));design.set(new DisplayDesign(doc));refresh.invoke(prefixes,player);
    Field open=PrefixController.class.getDeclaredField("open");open.setAccessible(true);open.setBoolean(prefixes,true);
    Method drain=PrefixController.class.getDeclaredMethod("drain");drain.setAccessible(true);for(int n=0;n<settings.features.period*2;n++)drain.invoke(prefixes);
    prefixes.animations(viewer->false);check(prefixes.prefix(player,player).contains("ONE"),"animation opt-out uses first custom prefix frame");
    check(prefixes.forViewer(prefixes.chat(id),id).contains("ONE"),"chat honors the same first-frame preference");
    prefixes.close();texts.close();luck.close();
  }

  private static void groups(){
    JsonObject doc=DisplayDesign.defaults(false).json();JsonArray styles=new JsonArray();styles.add(obj("{group:'vip',groups:['premium','elite'],prefixMode:'CUSTOM',prefix:{text:'<gold>[VIP]</gold>'}}"));doc.add("groupStyles",styles);DisplayDesign d=new DisplayDesign(doc);
    check(d.groupStyle("premium")==d.groupStyle("vip")&&d.groupStyle("ELITE")!=null,"shared group style resolves every target");
    check(d.groupRank("elite")==d.groupRank("vip"),"shared style shares priority");
    check(new DisplayDesign(d.json()).groupStyle("premium").prefix.text.contains("VIP"),"prefix survives restart");
    styles.add(obj("{group:'premium'}"));reject(()->new DisplayDesign(doc));
    reject(()->new DisplayDesign.GroupStyle(obj("{group:'vip',groups:['bad name']}")));
    reject(()->new DisplayDesign.GroupStyle(obj("{group:'vip',prefixMode:'UNKNOWN'}")));
  }
}
