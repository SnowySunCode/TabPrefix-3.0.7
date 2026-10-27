package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.time.*;
import java.util.*;

/** Calendar windows and one shared random draw per occurrence, never per tick or viewer. */
public final class BossSchedule {
  public final String mode, timezone, startTime, endTime;
  public final int intervalMinutes, durationSeconds;
  public final double chance;
  public final List<Integer> months, weekDays, monthDays, hours, minutes;
  public final List<ChanceRule> rules;
  private final ZoneId zone;

  public static final class ChanceRule {
    public final List<Integer> weekDays, hours;
    public final double chance;
    ChanceRule(JsonObject j) {
      weekDays = values(j, "weekDays", 1, 7);
      hours = values(j, "hours", 0, 23);
      chance = DisplayDesign.decimal(j, "chance", 25, 0, 100);
    }
    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.add("weekDays", array(weekDays)); j.add("hours", array(hours));
      j.addProperty("chance", chance); return j;
    }
  }

  public BossSchedule(JsonObject j) {
    mode = DisplayDesign.choice(j, "mode", "ALWAYS", "ALWAYS", "CALENDAR", "RANDOM");
    timezone = DisplayDesign.string(j, "timezone", "Europe/Moscow", 80);
    try { zone = ZoneId.of(timezone); }
    catch (DateTimeException e) { throw new IllegalArgumentException("schedule.timezone: unknown timezone"); }
    months = values(j,"months",1,12); weekDays = values(j,"weekDays",1,7);
    monthDays = values(j,"monthDays",1,31); hours = values(j,"hours",0,23);
    minutes = values(j,"minutes",0,59);
    startTime = DisplayDesign.string(j,"startTime","",5);
    endTime = DisplayDesign.string(j,"endTime","",5);
    if (startTime.isEmpty() != endTime.isEmpty()) throw new IllegalArgumentException("Set both start and end time");
    if (!startTime.isEmpty()) {
      if (!startTime.matches("[0-2][0-9]:[0-5][0-9]") || !endTime.matches("[0-2][0-9]:[0-5][0-9]"))
        throw new IllegalArgumentException("schedule.time: use HH:mm");
      try { LocalTime.parse(startTime); LocalTime.parse(endTime); }
      catch (DateTimeException e) { throw new IllegalArgumentException("schedule.time: invalid time"); }
    }
    intervalMinutes = DisplayDesign.integer(j,"intervalMinutes",30,1,44640);
    durationSeconds = DisplayDesign.integer(j,"durationSeconds",60,1,86400);
    if (mode.equals("RANDOM") && durationSeconds > intervalMinutes * 60L)
      throw new IllegalArgumentException("Random display duration must not exceed the draw interval");
    chance = DisplayDesign.decimal(j,"chance",25,0,100);
    List<ChanceRule> parsed = new ArrayList<>();
    if (j.has("rules")) {
      if (!j.get("rules").isJsonArray() || j.getAsJsonArray("rules").size()>16)
        throw new IllegalArgumentException("schedule.rules: maximum 16 rules");
      for (JsonElement e : j.getAsJsonArray("rules"))
        parsed.add(new ChanceRule(DisplayDesign.object(e,"schedule.rule")));
    }
    rules = Collections.unmodifiableList(parsed);
  }

  private static List<Integer> values(JsonObject j,String key,int min,int max) {
    SortedSet<Integer> out = new TreeSet<>();
    if (j.has(key)) {
      if (!j.get(key).isJsonArray() || j.getAsJsonArray(key).size()>64)
        throw new IllegalArgumentException("schedule."+key+": expected up to 64 values");
      for (JsonElement e : j.getAsJsonArray(key)) {
        JsonObject n = new JsonObject(); n.add("value",e);
        out.add(DisplayDesign.integer(n,"value",min,min,max));
      }
    }
    return Collections.unmodifiableList(new ArrayList<>(out));
  }
  private static boolean has(List<Integer> a,int n) { return a.isEmpty() || a.contains(n); }
  public boolean calendar(long millis) {
    ZonedDateTime now = Instant.ofEpochMilli(millis).atZone(zone);
    ZonedDateTime date=scheduleDate(now);
    if (!has(months,date.getMonthValue()) || !has(monthDays,date.getDayOfMonth())
        || !has(weekDays,date.getDayOfWeek().getValue()) || !has(hours,now.getHour())
        || !has(minutes,now.getMinute())) return false;
    if (startTime.isEmpty()) return true;
    LocalTime time = now.toLocalTime(), start = LocalTime.parse(startTime), end = LocalTime.parse(endTime);
    if (start.equals(end)) return true;
    return start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end)
        : !time.isBefore(start) || time.isBefore(end);
  }
  private ZonedDateTime scheduleDate(ZonedDateTime now) {
    if(!startTime.isEmpty()) {
      LocalTime start=LocalTime.parse(startTime),end=LocalTime.parse(endTime);
      if(start.isAfter(end)&&now.toLocalTime().isBefore(end))return now.minusDays(1);
    }
    return now;
  }
  public double probability(long millis) {
    ZonedDateTime now = Instant.ofEpochMilli(millis).atZone(zone);
    ZonedDateTime date=scheduleDate(now);
    for (ChanceRule r : rules)
      if (has(r.weekDays,date.getDayOfWeek().getValue()) && has(r.hours,now.getHour())) return r.chance;
    return chance;
  }
  public long occurrence(long millis) { return Math.floorDiv(millis,intervalMinutes * 60000L); }
  public String reason(String id,long millis,long salt) {
    if (mode.equals("ALWAYS")) return "";
    if (!calendar(millis)) return "SCHEDULE";
    if (mode.equals("CALENDAR")) return "";
    long event = occurrence(millis), start = event * intervalMinutes * 60000L;
    if (!calendar(start) || millis - start >= durationSeconds * 1000L) return "RANDOM_WAIT";
    // SplitMix64: stable for all viewers of this event; no mutation in previews.
    long n = salt ^ ((long)id.hashCode()<<32) ^ event;
    n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
    n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
    n ^= n >>> 31;
    double draw = (n >>> 11) * 0x1.0p-53 * 100;
    return draw < probability(start) ? "" : "RANDOM_MISS";
  }
  public JsonObject json() {
    JsonObject j=new JsonObject(); j.addProperty("mode",mode);j.addProperty("timezone",timezone);
    j.addProperty("startTime",startTime);j.addProperty("endTime",endTime);
    j.addProperty("chance",chance);j.addProperty("intervalMinutes",intervalMinutes);j.addProperty("durationSeconds",durationSeconds);
    j.add("months",array(months));j.add("weekDays",array(weekDays));j.add("monthDays",array(monthDays));
    j.add("hours",array(hours));j.add("minutes",array(minutes));
    JsonArray a=new JsonArray();for(ChanceRule r:rules)a.add(r.json());j.add("rules",a);return j;
  }
  private static JsonArray array(List<Integer> values) { JsonArray a=new JsonArray();for(int n:values)a.add(n);return a; }
}
