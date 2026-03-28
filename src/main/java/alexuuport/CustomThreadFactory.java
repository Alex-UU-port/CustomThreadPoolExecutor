package alexuuport;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фабрика потоков:
 * - даёт уникальные имена
 * - логирует создание
 * - логирует завершение
 */
public class CustomThreadFactory implements ThreadFactory {

    private final String poolName;
    private final AtomicInteger counter = new AtomicInteger(0);

    public CustomThreadFactory(String poolName) {
        this.poolName = poolName;
    }

    @Override
    public Thread newThread(Runnable r) {

        String name = poolName + "-worker-" + counter.incrementAndGet();

        PoolLogger.log("ThreadFactory",
                "Creating new thread: " + name);

        return new Thread(() -> {
            try {
                r.run();
            } finally {
                PoolLogger.log("Worker",
                        name + " terminated.");
            }
        }, name);
    }
}
