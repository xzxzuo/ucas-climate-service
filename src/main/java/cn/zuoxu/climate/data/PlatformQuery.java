package cn.zuoxu.climate.data;

import java.util.Map;
import java.util.Objects;

/** Validated query scope; SQL structure belongs in the mapper, never in request parameters. */
public record PlatformQuery(long datasetId, long resultVersion, Kind kind, Map<String,String> filters) {
    public enum Kind { THRESHOLDS, DAILY, STATION_ANNUAL, REGION_DAILY, REGION_ANNUAL }

    public PlatformQuery {
        if (datasetId < 1 || resultVersion < 1) throw new IllegalArgumentException("Invalid result scope");
        Objects.requireNonNull(kind);
        filters = Map.copyOf(filters);
    }
}
