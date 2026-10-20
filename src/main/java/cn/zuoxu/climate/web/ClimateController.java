package cn.zuoxu.climate.web;

import cn.zuoxu.climate.data.ClimateMapper;
import cn.zuoxu.climate.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class ClimateController {
    private final ClimateMapper mapper;
    private final ClimateQueryService query;
    private final ExportService export;
    private final AnalysisService analysis;
    private final QueryCache cache;
    public ClimateController(ClimateMapper mapper,ClimateQueryService query,ExportService export,AnalysisService analysis,QueryCache cache) {
        this.mapper=mapper;this.query=query;this.export=export;this.analysis=analysis;this.cache=cache;
    }
    @GetMapping("/meta") public Object meta() {
        return Map.of("analysis",mapper.state(),"baseline","1973-01-01 <= date < 1993-01-01",
            "quantile","empirical nearest rank: ceil(p*n), NULL excluded per variable",
            "units",Map.of("temperature","degrees Fahrenheit","precipitation","inches","humidity","ratio 0..1","annualFrequency","nonzero observed days"),
            "scope","bounded sample, not a representative climate dataset");
    }
    @GetMapping("/stations") public Object stations() { return mapper.stations(); }
    @GetMapping("/stations/{station}/thresholds") public Object threshold(@PathVariable @Pattern(regexp="[0-9]{11}") String station) { return response(query.threshold(station)); }
    @GetMapping("/stations/{station}/indicators") public Object daily(@PathVariable @Pattern(regexp="[0-9]{11}") String station,
        @RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(defaultValue="100") @Min(1) @Max(500) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset) {
        return query.daily(station,from,to,limit,offset);
    }
    @GetMapping("/stations/{station}/annual") public Object annual(@PathVariable @Pattern(regexp="[0-9]{11}") String station,@RequestParam @Min(1993) @Max(2100) int year) {
        return mapper.stationAnnual(station,year);
    }
    @GetMapping("/aggregates/daily") public Object regionDaily(@RequestParam LocalDate date,
        @RequestParam RegionLevel level,@RequestParam Metric metric,@RequestParam(required=false) String country,
        @RequestParam(required=false) String province,@RequestParam(required=false) String city,@RequestParam(required=false) String district) {
        return response(query.region(level,metric,country,province,city,district,date,null));
    }
    @GetMapping("/aggregates/annual") public Object regionAnnual(@RequestParam @Min(1993) @Max(2100) int year,
        @RequestParam RegionLevel level,@RequestParam Metric metric,@RequestParam(required=false) String country,
        @RequestParam(required=false) String province,@RequestParam(required=false) String city,@RequestParam(required=false) String district) {
        return response(query.region(level,metric,country,province,city,district,null,year));
    }
    @GetMapping("/exports/daily.csv") public void export(@RequestParam @Pattern(regexp="[0-9]{11}") String station,
        @RequestParam LocalDate from,@RequestParam LocalDate to,HttpServletResponse response) throws Exception {
        csvResponse(response,"climate-"+station);
        export.write(station,from,to,response.getWriter());
    }
    @GetMapping("/exports/thresholds.csv") public void exportThresholds(HttpServletResponse response) throws Exception {
        csvResponse(response,"thresholds");export.thresholds(response.getWriter());
    }
    @GetMapping("/exports/station-annual.csv") public void exportStationAnnual(
        @RequestParam @Pattern(regexp="[0-9]{11}") String station,@RequestParam @Min(1993) @Max(2100) int year,HttpServletResponse response) throws Exception {
        csvResponse(response,"station-annual");export.stationAnnual(station,year,response.getWriter());
    }
    @GetMapping("/exports/region-daily.csv") public void exportRegionDaily(@RequestParam LocalDate date,
        @RequestParam RegionLevel level,@RequestParam Metric metric,@RequestParam(required=false) String country,
        @RequestParam(required=false) String province,@RequestParam(required=false) String city,@RequestParam(required=false) String district,HttpServletResponse response) throws Exception {
        var p=query.regionParameters(level,metric,country,province,city,district,date,null);
        csvResponse(response,"region-daily");export.region(p,false,response.getWriter());
    }
    @GetMapping("/exports/region-annual.csv") public void exportRegionAnnual(@RequestParam @Min(1993) @Max(2100) int year,
        @RequestParam RegionLevel level,@RequestParam Metric metric,@RequestParam(required=false) String country,
        @RequestParam(required=false) String province,@RequestParam(required=false) String city,@RequestParam(required=false) String district,HttpServletResponse response) throws Exception {
        var p=query.regionParameters(level,metric,country,province,city,district,null,year);
        csvResponse(response,"region-annual");export.region(p,true,response.getWriter());
    }
    @PostMapping("/admin/rebuild") public Object rebuild() throws Exception { return analysis.rebuild(); }
    @GetMapping("/admin/cache") public Object cache() { return cache.stats(); }
    private ResponseEntity<?> response(QueryCache.Result r) { return ResponseEntity.ok().header("X-Cache",r.status()).body(r.data()); }
    private void csvResponse(HttpServletResponse response,String name) {
        response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename="+name+".csv");
    }
}
