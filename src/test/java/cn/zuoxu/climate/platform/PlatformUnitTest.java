package cn.zuoxu.climate.platform;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class PlatformUnitTest {
    @Test void executionLimitsReserveDatabaseCapacity(){
        assertThat(new PlatformExecutionSettings(2,1,10).writeThreads()).isEqualTo(2);
        assertThatThrownBy(()->new PlatformExecutionSettings(0,1,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PlatformExecutionSettings(2,1,6)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void csvNeutralizesTextFormulasButPreservesNumericNegatives(){
        assertThat(PlatformProcessor.csvCell("=SUM(A1)")).isEqualTo("'=SUM(A1)");assertThat(PlatformProcessor.csvCell("@evil")).isEqualTo("'@evil");
        assertThat(PlatformProcessor.csvCell(new BigDecimal("-10"))).isEqualTo(new BigDecimal("-10"));assertThat(PlatformProcessor.csvCell(null)).isNull();
    }
    @Test void filePathsRejectTraversal(){var p=new PlatformProperties("target/platform-unit",false,"");assertThatThrownBy(()->p.upload("../escape")).isInstanceOf(IllegalArgumentException.class);assertThat(p.export("12345678-1234-1234-1234-123456789abc",false).getFileName().toString()).endsWith(".csv");}
    @Test void passwordsEnforceBcryptByteLimit(){assertThatThrownBy(()->PlatformUsers.validatePassword("short")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->PlatformUsers.validatePassword("密".repeat(30))).isInstanceOf(IllegalArgumentException.class);PlatformUsers.validatePassword("valid-password-2026");}
}
