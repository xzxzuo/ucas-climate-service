package cn.zuoxu.climate.data;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface ClimateMapper {
    List<Map<String,Object>> stations();
    Map<String,Object> threshold(String station);
    List<Map<String,Object>> thresholds();
    List<Map<String,Object>> daily(@Param("station") String station,@Param("from") LocalDate from,@Param("to") LocalDate to,@Param("limit") int limit,@Param("offset") int offset);
    long countDaily(@Param("station") String station,@Param("from") LocalDate from,@Param("to") LocalDate to);
    List<Map<String,Object>> stationAnnual(@Param("station") String station,@Param("year") int year);
    List<Map<String,Object>> regionDaily(Map<String,Object> parameters);
    List<Map<String,Object>> regionAnnual(Map<String,Object> parameters);
    Map<String,Object> state();
}
