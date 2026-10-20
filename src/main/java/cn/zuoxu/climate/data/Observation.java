package cn.zuoxu.climate.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record Observation(String station, LocalDate date, String name, BigDecimal temp, BigDecimal precipitation,
                          BigDecimal humidity, BigDecimal max, BigDecimal min, String flags,
                          String country, String province, String city, String district) {
    public static Observation from(Map<String,String> row) {
        String station = row.getOrDefault("STATION", "");
        if (!station.matches("[0-9]{1,11}")) throw new IllegalArgumentException("Invalid station code");
        station = "0".repeat(11-station.length())+station;
        String flags = row.getOrDefault("FRSHTT", "");
        if (flags.endsWith(".0")) flags=flags.substring(0, flags.length()-2);
        if (flags.isBlank()) flags=null;
        else {
            if (!flags.matches("[01]{1,6}")) throw new IllegalArgumentException("Invalid FRSHTT");
            flags="0".repeat(6-flags.length())+flags;
        }
        BigDecimal humidity=decimal(row.get("HUMIDITY"), null);
        if (humidity!=null && (humidity.signum()<0 || humidity.compareTo(BigDecimal.ONE)>0))
            throw new IllegalArgumentException("Humidity must be a ratio between 0 and 1");
        BigDecimal precip=decimal(row.get("PRCP"), "99.99");
        if (precip!=null && precip.signum()<0) throw new IllegalArgumentException("Negative precipitation");
        return new Observation(station,LocalDate.parse(row.get("DATE")),text(row,"NAME",160),
            decimal(row.get("TEMP"),"9999.9"),precip,humidity,
            decimal(row.get("MAX"),"9999.9"),decimal(row.get("MIN"),"9999.9"),flags,
            text(row,"country",96),text(row,"province",96),text(row,"city",96),text(row,"district",96));
    }
    private static BigDecimal decimal(String value,String sentinel) {
        if (value==null || value.isBlank() || value.equalsIgnoreCase("nan")) return null;
        BigDecimal n=new BigDecimal(value);
        return sentinel!=null && n.compareTo(new BigDecimal(sentinel))==0 ? null : n;
    }
    private static String text(Map<String,String> row,String key,int max) {
        String value=row.getOrDefault(key, "").strip();
        if (value.length()>max) throw new IllegalArgumentException(key+" is too long");
        return value;
    }
    public Object[] parameters() {
        return new Object[]{station,date,name,temp,precipitation,humidity,max,min,flags,country,province,city,district};
    }
}
