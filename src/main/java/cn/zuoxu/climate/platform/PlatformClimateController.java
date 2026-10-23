package cn.zuoxu.climate.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** The platform's Spring MVC climate entry points; account/task administration is separate. */
@RestController
@RequestMapping("/platform/api/datasets/{id}")
public class PlatformClimateController {
    private final PlatformUsers users;
    private final PlatformQueries queries;
    private final PlatformTasks tasks;

    public PlatformClimateController(PlatformUsers users,PlatformQueries queries,PlatformTasks tasks){
        this.users=users;this.queries=queries;this.tasks=tasks;
    }
    @GetMapping("/stations")
    public Object stations(@PathVariable long id){return queries.stations(users.current(),id);}

    @GetMapping("/analysis")
    public ResponseEntity<?> analysis(@PathVariable long id,@RequestParam Map<String,String> parameters){
        var result=queries.query(users.current(),id,parameters);
        return ResponseEntity.ok().header("X-Cache",result.cache()).body(result.body());
    }
    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object export(@PathVariable long id,@RequestBody Map<String,String> parameters){
        var actor=users.current();var selection=queries.prepare(actor,id,parameters,null);
        return tasks.enqueue(actor,id,"EXPORT",null,selection.parameters(),selection.version(),null);
    }
}
