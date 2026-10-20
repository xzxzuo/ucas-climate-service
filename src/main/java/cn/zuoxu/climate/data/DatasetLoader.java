package cn.zuoxu.climate.data;

import cn.zuoxu.climate.service.AnalysisService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.Map;

@Service
public class DatasetLoader {
    private final ImportService importer;
    private final AnalysisService analysis;
    public DatasetLoader(ImportService importer,AnalysisService analysis) { this.importer=importer;this.analysis=analysis; }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> load(Path path) throws Exception {
        int rows=importer.importFile(path);
        return Map.of("importedRecords",rows,"analysis",analysis.rebuild());
    }
}
