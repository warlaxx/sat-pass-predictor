package dev.abdallah.satpass.access;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "account.enabled", havingValue = "true")
public class AccountPageController {
    @GetMapping({"/account", "/account/"})
    public String index() { return "forward:/account/index.html"; }
}
