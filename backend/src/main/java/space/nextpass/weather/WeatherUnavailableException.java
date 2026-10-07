package space.nextpass.weather;

/** No forecast to give: MET Norway failed, or the upstream budget is spent and none is kept. */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String message) {
        super(message);
    }

    public WeatherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
