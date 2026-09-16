package dev.abdallah.satpass.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Path to the orekit-data directory (EOP, leap seconds, gravity models...).
 * Never committed: downloaded by scripts/fetch-orekit-data.sh.
 */
@ConfigurationProperties("orekit")
public record OrekitProperties(Path dataPath) {}
