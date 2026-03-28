package alexuuport;

import java.util.concurrent.*;

public class Main {

    public static void main(String[] args) throws Exception {

        CustomThreadPoolExecutor pool =
                new CustomThreadPoolExecutor(
                        2,      // core
                        4,      // max
                        5,      // queue per worker
                        5,      // keepAlive
                        TimeUnit.SECONDS
                );

        // Отправляем 15 задач (специально больше чем пул выдержит)
        for (int i = 1; i <= 15; i++) {

            int taskId = i;

            pool.execute(() -> {
                PoolLogger.log("Задача",
                        "Задача " + taskId + " началась.");

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}

                PoolLogger.log("Задача",
                        "Задача " + taskId + " завершилась.");
            });
        }

        Thread.sleep(15000);

        pool.shutdown();
    }
}