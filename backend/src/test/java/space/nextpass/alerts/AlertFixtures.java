package space.nextpass.alerts;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import space.nextpass.domain.SatellitePass;
import space.nextpass.domain.SubSatellitePoint;
import space.nextpass.domain.TrackPoint;
import space.nextpass.weather.CloudForecast;

/** Passes, forecasts and subscriptions built by hand, without Orekit or a network. */
final class AlertFixtures {

    /** 19:00 UTC on 8 October 2026: 21:00 in Paris. */
    static final Instant EVENING = Instant.parse("2026-10-08T19:00:00Z");
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /** One sample a minute from {@code start}; magnitude only where the sample is visible. */
    record Sample(double elevationDeg, boolean visible, Double magnitude) {}

    static Sample dark(double elevation) {
        return new Sample(elevation, false, null);
    }

    static Sample seen(double elevation) {
        return new Sample(elevation, true, null);
    }

    static Sample seen(double elevation, double magnitude) {
        return new Sample(elevation, true, magnitude);
    }

    static SatellitePass pass(Instant start, Sample... samples) {
        List<TrackPoint> track = new ArrayList<>();
        int highest = 0;
        for (int i = 0; i < samples.length; i++) {
            Sample sample = samples[i];
            track.add(new TrackPoint(start.plusSeconds(60L * i), (200 + 10 * i) % 360, sample.elevationDeg(), 800, 0,
                    new SubSatellitePoint(45, 5, 420), sample.visible(), sample.visible(), sample.magnitude()));
            if (sample.elevationDeg() > samples[highest].elevationDeg()) {
                highest = i;
            }
        }
        return new SatellitePass(track.getFirst(), track.get(highest), track.getLast(), track);
    }

    /** Hourly steps from 18:00 UTC, all at {@code cloudPercent}. */
    static CloudForecast clouds(int cloudPercent) {
        List<CloudForecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            hours.add(new CloudForecast.Hour(Instant.parse("2026-10-08T18:00:00Z").plusSeconds(3600L * i), cloudPercent, 1));
        }
        return new CloudForecast(45.8, 4.8, Instant.parse("2026-10-08T12:00:00Z"), hours);
    }

    static AlertSubscription subscription(String locale) {
        return new AlertSubscription(7, "ada@example.org", 25544, 45.76, 4.84, 30, 25, null, PARIS, locale,
                "tok_tok_tok_tok_tok_tok_tok_tok_tok_tok_tok", Instant.parse("2026-10-07T10:00:00Z"), null, null);
    }

    private AlertFixtures() {}
}
