package cn.zuoxu.climate.service;

import cn.zuoxu.climate.data.ClimateMapper;
import cn.zuoxu.climate.web.Metric;
import cn.zuoxu.climate.web.RegionLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClimateQueryService {
    public record Page(List<Map<String,Object>> items,int limit,int offset,boolean hasMore) {}
    private final ClimateMapper mapper;
    private final QueryCache cache;
    public ClimateQueryService(ClimateMapper mapper,QueryCache cache) { this.mapper=mapper;this.cache=cache; }
    public QueryCache.Result threshold(String station) {
        return cache.read("threshold",station,()->{
            Map<String,Object> result=mapper.threshold(station);
            if (result==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No baseline threshold for this station");
            return result;
        });
    }
    public Page daily(String station,LocalDate from,LocalDate to,int limit,int offset) {
        validateDates(from,to);
        var rows=mapper.daily(station,from,to,limit+1,offset);
        boolean more=rows.size()>limit;
        return new Page(more?rows.subList(0,limit):rows,limit,offset,more);
    }
    public QueryCache.Result region(RegionLevel level,Metric metric,String country,String province,String city,String district,LocalDate date,Integer year) {
        Map<String,Object> p=regionParameters(level,metric,country,province,city,district,date,year);
        return cache.read(date!=null?"region-day":"region-year",p.toString(),()->date!=null?mapper.regionDaily(p):mapper.regionAnnual(p));
    }
    public Map<String,Object> regionParameters(RegionLevel level,Metric metric,String country,String province,String city,String district,LocalDate date,Integer year) {
        if (date!=null && metric.ordinal()>Metric.CTD1.ordinal()) throw new IllegalArgumentException("Daily region aggregation supports seven magnitude metrics only");
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("level",level.name());p.put("metric",metric.name());
        p.put("country",clean(country));p.put("province",clean(province));p.put("city",clean(city));p.put("district",clean(district));
        if (date!=null) p.put("date",date);else p.put("year",year);
        return p;
    }
    private String clean(String input) {
        if (input==null) return "";
        String value=input.strip();
        if (value.length()>96) throw new IllegalArgumentException("Region value exceeds 96 characters");
        return value;
    }
    public static void validateDates(LocalDate from,LocalDate to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to (exclusive)");
        if (from.getYear()<1993 || to.getYear()>2101) throw new IllegalArgumentException("Analysis dates must be within 1993..2100");
    }
}
