package cn.zuoxu.climate.bootstrap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.Map;

@Service
public class SampleLoader {
    private final SampleImporter importer;
    private final SampleAnalysis analysis;
    public SampleLoader(SampleImporter importer,SampleAnalysis analysis) { this.importer=importer;this.analysis=analysis; }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> load(Path path) throws Exception {
        int rows=importer.importFile(path);
        return Map.of("importedRecords",rows,"analysis",analysis.rebuild());
    }
}
