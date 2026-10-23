package cn.zuoxu.climate.data;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** The platform's climate read repository, shared by interactive queries and export tasks. */
@Mapper
public interface PlatformClimateMapper {
    List<Map<String,Object>> stations(@Param("datasetId") long datasetId);
    List<Map<String,Object>> versions(@Param("datasetId") long datasetId);
    boolean hasVersion(@Param("datasetId") long datasetId, @Param("version") long version);
    List<Map<String,Object>> selectPage(@Param("q") PlatformQuery query, @Param("limit") int limit, @Param("offset") int offset);
    long count(@Param("q") PlatformQuery query);
}
