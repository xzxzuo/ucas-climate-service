package cn.zuoxu.climate.web;

import cn.zuoxu.climate.incremental.IncrementalJobStore;
import cn.zuoxu.climate.incremental.IncrementalService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/incremental")
public class IncrementalController {
    private final IncrementalService service;
    private final IncrementalJobStore jobs;
    public IncrementalController(IncrementalService service,IncrementalJobStore jobs) { this.service=service;this.jobs=jobs; }
    @GetMapping("/status") public Object status() { return service.status(); }
    @GetMapping("/jobs") public Object jobs(@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) { return jobs.latest(limit,offset); }
    @PostMapping("/run") public Object run(@RequestParam(defaultValue="false") boolean retryFailed) {
        return service.runOnce("MANUAL",retryFailed);
    }
}
