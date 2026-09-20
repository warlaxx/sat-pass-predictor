package dev.abdallah.satpass.access;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Operator-only CLI; no unauthenticated key-creation endpoint or browser-held admin secret. */
@Component
@ConditionalOnProperty(name = "api-access.command")
public class ApiKeyCommand implements ApplicationRunner {
    private final AccessService access;
    private final ConfigurableApplicationContext context;
    public ApiKeyCommand(AccessService access, ConfigurableApplicationContext context) {
        this.access = access;
        this.context = context;
    }

    @Override public void run(ApplicationArguments args) {
        try {
            switch (required(args, "api-access.command")) {
                case "issue" -> {
                    var key = access.issue(required(args, "owner"),
                            integer(args, "daily-limit", 10000), integer(args, "minute-limit", 60));
                    System.out.println("Key ID: " + key.id());
                    System.out.println("API key (shown once): " + key.secret());
                }
                case "revoke" -> {
                    if (!access.revoke(UUID.fromString(required(args, "key-id")))) {
                        throw new IllegalArgumentException("Unknown key ID");
                    }
                    System.out.println("Key revoked");
                }
                case "usage" -> access.usage(UUID.fromString(required(args, "key-id"))).forEach(System.out::println);
                default -> throw new IllegalArgumentException("Expected issue, revoke or usage");
            }
        } finally {
            context.close();
        }
    }
    private static String required(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Exactly one --" + name + " is required");
        }
        return values.getFirst();
    }
    private static int integer(ApplicationArguments args, String name, int fallback) {
        return args.containsOption(name) ? Integer.parseInt(required(args, name)) : fallback;
    }
}
