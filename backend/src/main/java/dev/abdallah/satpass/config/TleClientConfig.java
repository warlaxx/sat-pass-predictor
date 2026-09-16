package dev.abdallah.satpass.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cablage du client HTTP vers CelesTrak.
 *
 * <p>Le {@link RestClient} est construit ici plutot que dans le client lui-meme : les
 * tests lui injectent un {@code RestClient} branche sur {@code MockRestServiceServer},
 * sans avoir a neutraliser une fabrique de requetes deja posee.
 *
 * <p>Il part de {@code RestClient.builder()} et non du {@code RestClient.Builder}
 * auto-configure : dans Spring Boot 4, cette auto-configuration vit dans un module
 * separe que {@code starter-web} ne tire pas. Plutot que d'ajouter une dependance pour
 * un unique client dont on regle deja tout a la main, on le construit sans elle. A
 * savoir si un second client apparait : il n'heritera d'aucun reglage commun.
 *
 * <p>Les deux timeouts sont explicites, et vivent a deux endroits differents parce que
 * ce sont deux choses differentes : l'etablissement de la connexion appartient au
 * {@link HttpClient} du JDK, la lecture de la reponse a la fabrique de Spring. Sans eux,
 * les defauts sont infinis : un CelesTrak qui accepte la connexion puis ne repond jamais
 * immobiliserait des threads du serveur, et la « degradation propre » du magasin ne se
 * declencherait jamais — elle tiendrait entierement a la bonne volonte du service
 * distant.
 */
@Configuration
@EnableConfigurationProperties(TleProperties.class)
public class TleClientConfig {

    @Bean
    public RestClient celestrakRestClient(TleProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Horloge injectee plutot que {@code Instant.now()} disperse dans le code : l'age
     * d'un TLE et le declenchement d'un rafraichissement sont des regles metier, et une
     * regle qui depend de l'heure murale ne se teste qu'en attendant.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
