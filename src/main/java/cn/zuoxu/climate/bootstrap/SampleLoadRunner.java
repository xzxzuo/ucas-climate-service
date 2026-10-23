package cn.zuoxu.climate.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;

@Component
public class SampleLoadRunner implements ApplicationRunner {
    private final SampleLoader loader;
    private final ConfigurableApplicationContext context;
    private final JdbcTemplate jdbc;
    public SampleLoadRunner(SampleLoader loader,ConfigurableApplicationContext context,JdbcTemplate jdbc) { this.loader=loader;this.context=context;this.jdbc=jdbc; }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("app.import-file")) return;
        if (args.containsOption("app.import-if-empty") && jdbc.queryForObject("SELECT COUNT(*) FROM raw_observation",Long.class)>0) {
            System.out.println("Existing dataset retained; initial import skipped.");
        } else {
            System.out.println("IMPORT_RESULT "+loader.load(Path.of(args.getOptionValues("app.import-file").get(0))));
        }
        if (args.containsOption("app.exit-after-import")) SpringApplication.exit(context,()->0);
    }
}
