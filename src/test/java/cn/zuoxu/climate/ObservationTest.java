package cn.zuoxu.climate;

import cn.zuoxu.climate.data.Observation;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ObservationTest {
    private Map<String,String> row() {
        return new HashMap<>(Map.of("STATION","7027099999","DATE","1993-01-01","TEMP","42.0","PRCP","0.2","HUMIDITY","0.6","MAX","50","MIN","30","FRSHTT","10000"));
    }
    @Test void restoresLeadingZerosWithoutShiftingWeatherMeaning() {
        var o=Observation.from(row());assertThat(o.station()).isEqualTo("07027099999");
        assertThat(o.flags()).isEqualTo("010000");
    }
    @Test void convertsMissingSentinelsToNull() {
        var r=row();r.put("TEMP","9999.9");r.put("PRCP","99.99");r.put("MAX","");r.put("MIN","NaN");r.put("FRSHTT","");
        var o=Observation.from(r);assertThat(o.temp()).isNull();assertThat(o.precipitation()).isNull();assertThat(o.max()).isNull();assertThat(o.min()).isNull();assertThat(o.flags()).isNull();
    }
    @Test void rejectsBadWeatherAndHumidity() {
        var r=row();r.put("FRSHTT","120000");assertThatThrownBy(()->Observation.from(r)).isInstanceOf(IllegalArgumentException.class);
        r.put("FRSHTT","000000");r.put("HUMIDITY","60");assertThatThrownBy(()->Observation.from(r)).isInstanceOf(IllegalArgumentException.class);
    }
}
