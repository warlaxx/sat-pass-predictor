package dev.abdallah.satpass.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chemin vers le repertoire orekit-data (EOP, sauts de seconde, modeles de gravite...).
 * Jamais commite : telecharge par scripts/fetch-orekit-data.sh.
 */
@ConfigurationProperties("orekit")
public record OrekitProperties(Path dataPath) {}
