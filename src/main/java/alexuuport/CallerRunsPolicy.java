package alexuuport;

import java.util.concurrent.RejectedExecutionException;

/**
 * Выбранная стратегия:
 * CallerRunsPolicy
 *
 * Почему:
 *  - задачи не теряются
 *  - создаётся естественный back-pressure
 *
 * Недостатки:
 *  - блокируется вызывающий поток
 *  - может увеличить latency
 */
public class CallerRunsPolicy implements RejectionHandler {

    @Override
    public void reject(Runnable task, CustomThreadPoolExecutor executor) {

        if (!executor.isShutdown()) {

            PoolLogger.log("Отказ",
                    "Пул перегружен. Задача будет выполнена в вызывающем потоке: "
                            + Thread.currentThread().getName());

            task.run();

        } else {
            PoolLogger.log("Отказ",
                    "Пул завершён. Задача отклонена.");
            throw new RejectedExecutionException("Пул остановлен");
        }
    }
}
