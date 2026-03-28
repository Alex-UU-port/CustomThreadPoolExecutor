package alexuuport;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Логгер.
 * Позволяет видеть состояние системы в любой момент.
 */
public class PoolLogger {

    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static synchronized void log(String component, String message) {
        System.out.printf("[%s] [%s] %s%n",
                LocalTime.now().format(formatter),
                component,
                message);
    }
}
