package cn.zuoxu.climate.platform;

import cn.zuoxu.climate.data.PlatformClimateMapper;
import cn.zuoxu.climate.data.PlatformQuery;
import cn.zuoxu.climate.service.QueryCache;
import cn.zuoxu.climate.web.Metric;
import cn.zuoxu.climate.web.RegionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

/** Spring MVC's climate query service: authorization -> validation -> cache -> MyBatis. */
@Service
public class PlatformQueries {
    public record Selection(long dataset,long version,PlatformQuery query,Map<String,String> parameters){}
    public record Response(Object body,String cache){}
    private final PlatformClimateMapper mapper;
    private final PlatformAccess access;
    private final QueryCache cache;
    private final ObjectMapper json;

    public PlatformQueries(PlatformClimateMapper mapper,PlatformAccess access,QueryCache cache,ObjectMapper json){
        this.mapper=mapper;this.access=access;this.cache=cache;this.json=json;
    }
    public List<Map<String,Object>> stations(PlatformUsers.Account user,long dataset){
        access.dataset(user,dataset,false);return mapper.stations(dataset);
    }
    public List<Map<String,Object>> versions(PlatformUsers.Account user,long dataset){
        access.dataset(user,dataset,false);return mapper.versions(dataset);
    }
    public Selection prepare(PlatformUsers.Account user,long dataset,Map<String,String> request,Long pinnedVersion){
        var d=access.dataset(user,dataset,false);Long version=pinnedVersion;
        if(version==null&&request.containsKey("version")&&!request.get("version").isBlank())version=Long.parseLong(request.get("version"));
        if(version==null&&d.get("active_result_version")!=null)version=PlatformStore.number(d,"active_result_version");
        if(version==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"No published result; submit an analysis task first");
        if(!mapper.hasVersion(dataset,version))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Result version not found");
        PlatformQuery.Kind kind;
        try{kind=PlatformQuery.Kind.valueOf(request.getOrDefault("kind","THRESHOLDS").toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Unknown query kind");}
        Map<String,String> p=new TreeMap<>();p.put("kind",kind.name());
        switch(kind){
            case THRESHOLDS -> { }
            case DAILY -> {
                String station=station(request);LocalDate from=date(request.getOrDefault("from","1993-01-01")),to=date(request.getOrDefault("to","2024-01-01"));
                if(!from.isBefore(to)||from.isBefore(LocalDate.of(1993,1,1))||to.isAfter(LocalDate.of(2101,1,1)))throw new IllegalArgumentException("Expected 1993 <= from < to <= 2101 (exclusive end)");
                p.put("station",station);p.put("from",from.toString());p.put("to",to.toString());
            }
            case STATION_ANNUAL -> {p.put("station",station(request));p.put("year",Integer.toString(year(request)));}
            case REGION_DAILY,REGION_ANNUAL -> {
                String level=request.getOrDefault("level","COUNTRY"),metric=request.getOrDefault("metric","HTD");
                try{RegionLevel.valueOf(level);Metric.valueOf(metric);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid region level or metric");}
                p.put("level",level);p.put("metric",metric);
                if(kind==PlatformQuery.Kind.REGION_DAILY)p.put("date",date(request.getOrDefault("date","1993-01-01")).toString());
                else p.put("year",Integer.toString(year(request)));
                for(String column:List.of("country","province","city","district")){
                    String value=request.getOrDefault(column,"");if(value.length()>96)throw new IllegalArgumentException("Region name too long");
                    if(!value.isBlank())p.put(column,value);
                }
            }
        }
        Map<String,String> parameters=Map.copyOf(p);
        return new Selection(dataset,version,new PlatformQuery(dataset,version,kind,parameters),parameters);
    }
    @Transactional(readOnly=true)
    public Response query(PlatformUsers.Account user,long dataset,Map<String,String> request){
        // Never put this check inside the cache loader: a warm cache must not bypass a revoked share.
        Selection selection=prepare(user,dataset,request,null);
        int limit=integer(request,"limit",100,1,500),offset=integer(request,"offset",0,0,1000000);
        if(selection.query().kind()==PlatformQuery.Kind.DAILY)return new Response(load(selection,limit,offset),"BYPASS");
        String parameters;
        try{parameters=json.writeValueAsString(new TreeMap<>(selection.parameters()))+":"+limit+":"+offset;}
        catch(Exception e){throw new IllegalStateException(e);}
        var result=cache.readScoped(dataset,selection.version(),selection.query().kind().name(),parameters,()->load(selection,limit,offset));
        return new Response(result.data(),result.status());
    }
    private Map<String,Object> load(Selection selection,int limit,int offset){
        var items=mapper.selectPage(selection.query(),limit,offset);long total=mapper.count(selection.query());
        return Map.of("datasetId",selection.dataset(),"resultVersion",selection.version(),"parameters",selection.parameters(),"items",items,"total",total,"limit",limit,"offset",offset);
    }
    public List<Map<String,Object>> exportRows(PlatformUsers.Account user,long dataset,Map<String,String> parameters,long version){
        Selection selection=prepare(user,dataset,parameters,version);
        var rows=mapper.selectPage(selection.query(),20001,0);
        if(rows.size()>20000)throw new IllegalArgumentException("Export exceeds 20000 rows; narrow your date range");return rows;
    }
    private static String station(Map<String,String> p){String value=p.getOrDefault("station","");if(!value.matches("[0-9]{11}"))throw new IllegalArgumentException("An 11-digit station is required");return value;}
    private static int year(Map<String,String> p){return integer(p,"year",1993,1993,2100);}
    private static int integer(Map<String,String> p,String key,int fallback,int min,int max){int n=Integer.parseInt(p.getOrDefault(key,Integer.toString(fallback)));if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;}
    private static LocalDate date(String value){try{return LocalDate.parse(value);}catch(Exception e){throw new IllegalArgumentException("Expected ISO date YYYY-MM-DD");}}
}
