package dev.abdallah.satpass.api;

import dev.abdallah.satpass.passes.PassQueryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same validated computation and DTO contract; distinct authenticated entry point. */
@RestController
@RequestMapping("/v1/passes")
@SecurityRequirement(name = "apiKey")
@Tag(name = "Versioned passes", description = "API key required; admitted requests count toward daily and minute limits")
public class VersionedPassController extends PassController {
    public VersionedPassController(PassQueryService service) { super(service); }
}
