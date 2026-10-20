package cn.zuoxu.climate.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class PlatformQueries {
    public record Selection(long dataset,long version,String sql,List<Object> args,Map<String,String> parameters){}
    public record Response(Map<String,Object> body,String cache){}
    private final PlatformStore store;
    private final PlatformAccess access;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean cacheEnabled;
    public PlatformQueries(PlatformStore store,PlatformAccess access,StringRedisTemplate redis,ObjectMapper json,@Value("${app.cache-enabled:true}")boolean enabled){this.store=store;this.access=access;this.redis=redis;this.json=json;this.cacheEnabled=enabled;}
    public List<Map<String,Object>> stations(PlatformUsers.Account user,long dataset){
        access.dataset(user,dataset,false);return store.jdbc.queryForList("""
          SELECT station_code stationCode,MAX(station_name) name,MAX(country) country,MAX(province) province,MAX(city) city,
          MIN(observation_date) firstDate,MAX(observation_date) lastDate,COUNT(*) observations
          FROM platform_raw_observation WHERE dataset_id=? GROUP BY station_code ORDER BY station_code LIMIT 200
          """,dataset);
    }
    public List<Map<String,Object>> versions(PlatformUsers.Account user,long dataset){
        access.dataset(user,dataset,false);return store.jdbc.queryForList("SELECT * FROM platform_analysis_run WHERE dataset_id=? ORDER BY result_version DESC LIMIT 100",dataset);
    }
    public Selection prepare(PlatformUsers.Account user,long dataset,Map<String,String> request,Long pinnedVersion){
        var d=access.dataset(user,dataset,false);Long version=pinnedVersion;
        if(version==null&&request.containsKey("version")&&!request.get("version").isBlank())version=Long.parseLong(request.get("version"));
        if(version==null&&d.get("active_result_version")!=null)version=PlatformStore.number(d,"active_result_version");
        if(version==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"No published result; submit an analysis task first");
        if(store.one("SELECT result_version FROM platform_analysis_run WHERE dataset_id=? AND result_version=?",dataset,version)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Result version not found");
        Map<String,String> p=new TreeMap<>();String kind=request.getOrDefault("kind","THRESHOLDS").toUpperCase(Locale.ROOT);p.put("kind",kind);
        List<Object> args=new ArrayList<>(List.of(dataset,version));String sql;
        switch(kind){
            case "THRESHOLDS" -> sql="SELECT station_code stationCode,temp10,temp90,prcp95,prcp99,humidity5,temp_samples tempSamples,prcp_samples prcpSamples,humidity_samples humiditySamples FROM platform_station_threshold WHERE dataset_id=? AND result_version=?";
            case "DAILY" -> {
                String station=station(request);LocalDate from=date(request.getOrDefault("from","1993-01-01")),to=date(request.getOrDefault("to","2024-01-01"));
                if(!from.isBefore(to)||from.isBefore(LocalDate.of(1993,1,1))||to.isAfter(LocalDate.of(2101,1,1)))throw new IllegalArgumentException("Expected 1993 <= from < to <= 2101 (exclusive end)");
                p.put("station",station);p.put("from",from.toString());p.put("to",to.toString());args.addAll(List.of(station,from,to));
                sql="SELECT station_code stationCode,observation_date `date`,ltd,htd,hr,ehr,lh,htd1,ctd1,fog,rain,snow,hail,thunder,tornado,country,province,city,district FROM platform_daily_indicator WHERE dataset_id=? AND result_version=? AND station_code=? AND observation_date>=? AND observation_date<?";
            }
            case "STATION_ANNUAL" -> {
                String station=station(request);int year=year(request);p.put("station",station);p.put("year",Integer.toString(year));args.addAll(List.of(station,year));
                sql="SELECT station_code stationCode,observation_year `year`,metric,nonzero_days nonzeroDays,valid_days validDays,country,province,city,district FROM platform_station_year_stat WHERE dataset_id=? AND result_version=? AND station_code=? AND observation_year=?";
            }
            case "REGION_DAILY","REGION_ANNUAL" -> {
                boolean daily=kind.equals("REGION_DAILY");String level=request.getOrDefault("level","COUNTRY"),metric=request.getOrDefault("metric","HTD");
                try{cn.zuoxu.climate.web.RegionLevel.valueOf(level);cn.zuoxu.climate.web.Metric.valueOf(metric);}catch(Exception e){throw new IllegalArgumentException("Invalid region level or metric");}
                p.put("level",level);p.put("metric",metric);
                String time=daily?date(request.getOrDefault("date","1993-01-01")).toString():Integer.toString(year(request));p.put(daily?"date":"year",time);args.add(daily?LocalDate.parse(time):Integer.parseInt(time));args.add(level);args.add(metric);
                sql=daily?"SELECT observation_date `date`,region_level level,country,province,city,district,metric,avg_value avgValue,min_value minValue,max_value maxValue,median_value medianValue,valid_station_count validStationCount FROM platform_region_daily_stat WHERE dataset_id=? AND result_version=? AND observation_date=? AND region_level=? AND metric=?":
                    "SELECT observation_year `year`,region_level level,country,province,city,district,metric,avg_days avgDays,min_days minDays,max_days maxDays,median_days medianDays,station_count stationCount FROM platform_region_year_stat WHERE dataset_id=? AND result_version=? AND observation_year=? AND region_level=? AND metric=?";
                for(String column:List.of("country","province","city","district")){String value=request.getOrDefault(column,"");if(value.length()>96)throw new IllegalArgumentException("Region name too long");if(!value.isBlank()){sql+=" AND "+column+"=?";args.add(value);p.put(column,value);}}
            }
            default -> throw new IllegalArgumentException("Unknown query kind");
        }
        String order=switch(kind){case "THRESHOLDS"->"stationCode";case "DAILY"->"`date`";case "STATION_ANNUAL"->"metric";default->"country,province,city,district";};
        return new Selection(dataset,version,sql+" ORDER BY "+order,List.copyOf(args),Map.copyOf(p));
    }
    public Response query(PlatformUsers.Account user,long dataset,Map<String,String> request){
        Selection selection=prepare(user,dataset,request,null);int limit=integer(request,"limit",100,1,500),offset=integer(request,"offset",0,0,1000000);
        String key;
        try{String content=json.writeValueAsString(new TreeMap<>(selection.parameters()))+":"+limit+":"+offset;
            key="climate-platform:"+dataset+":"+selection.version()+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
        boolean cache=cacheEnabled&&!selection.parameters().get("kind").equals("DAILY"),unavailable=false;
        if(cache)try{String value=redis.opsForValue().get(key);if(value!=null){Map<String,Object> cached=json.readerFor(new TypeReference<Map<String,Object>>(){}).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);return new Response(cached,"HIT");}}catch(Exception e){unavailable=true;}
        List<Object> args=new ArrayList<>(selection.args());args.add(limit);args.add(offset);
        var items=store.jdbc.queryForList(selection.sql()+" LIMIT ? OFFSET ?",args.toArray());
        long total=store.jdbc.queryForObject("SELECT COUNT(*) FROM ("+selection.sql()+") result_rows",Long.class,selection.args().toArray());
        Map<String,Object> body=Map.of("datasetId",dataset,"resultVersion",selection.version(),"parameters",selection.parameters(),"items",items,"total",total,"limit",limit,"offset",offset);
        if(cache&&!unavailable)try{redis.opsForValue().set(key,json.writeValueAsString(body),Duration.ofMinutes(30));}catch(Exception e){unavailable=true;}
        return new Response(body,!cache?"BYPASS":unavailable?"UNAVAILABLE":"MISS");
    }
    public List<Map<String,Object>> exportRows(PlatformUsers.Account user,long dataset,Map<String,String> parameters,long version){
        Selection selection=prepare(user,dataset,parameters,version);var rows=store.jdbc.queryForList(selection.sql()+" LIMIT 20001",selection.args().toArray());
        if(rows.size()>20000)throw new IllegalArgumentException("Export exceeds 20000 rows; narrow your date range");return rows;
    }
    private static String station(Map<String,String> p){String value=p.getOrDefault("station","");if(!value.matches("[0-9]{11}"))throw new IllegalArgumentException("An 11-digit station is required");return value;}
    private static int year(Map<String,String> p){return integer(p,"year",1993,1993,2100);}
    private static int integer(Map<String,String> p,String key,int fallback,int min,int max){int n=Integer.parseInt(p.getOrDefault(key,Integer.toString(fallback)));if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;}
    private static LocalDate date(String value){try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException("Expected ISO date YYYY-MM-DD");}}
}
