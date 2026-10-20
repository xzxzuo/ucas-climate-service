package cn.zuoxu.climate.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.sql.Statement;
import java.util.*;

@Component
public class PlatformStore {
    public final JdbcTemplate jdbc;
    public PlatformStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public long insert(String sql,Object... parameters) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<parameters.length;i++)statement.setObject(i+1,parameters[i]);return statement;},keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);
    }
    public void audit(long actor,Long dataset,String action,String target) {
        jdbc.update("INSERT INTO platform_audit(actor_id,dataset_id,action,target_id) VALUES(?,?,?,?)",actor,dataset,action,target);
    }
    public static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    public static boolean flag(Map<String,Object> row,String field) {
        Object value=row.get(field);return value instanceof Boolean b?b:((Number)value).intValue()!=0;
    }
}
