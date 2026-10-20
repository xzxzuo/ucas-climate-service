package cn.zuoxu.climate.platform;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class PlatformPage {
    @GetMapping({"/","/platform"}) public String platform(){return "redirect:/platform.html";}
}
