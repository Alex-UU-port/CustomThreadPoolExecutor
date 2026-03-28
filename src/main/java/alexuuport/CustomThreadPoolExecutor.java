package alexuuport;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Кастомный пул потоков.
 *
 * Реализовано:
 *  - Round Robin распределение задач
 *  - Отдельная очередь на каждый Worker
 *  - Idle timeout (keepAliveTime)
 *  - CallerRunsPolicy (механизм back-pressure)
 *  - Потокобезопасная коллекция workers
 *  - Подробное логирование на русском языке
 */
public class CustomThreadPoolExecutor implements CustomExecutor {

    // ====== Конфигурация пула ======

    private final int corePoolSize;   // минимальное количество потоков
    private final int maxPoolSize;    // максимальное количество потоков
    private final int queueSize;      // размер очереди на одного worker
    private final long keepAliveTime; // время простоя перед завершением
    private final TimeUnit timeUnit;

    // ====== Состояние пула ======

    // Потокобезопасный список рабочих потоков
    private final List<Worker> workers = new CopyOnWriteArrayList<>();

    // Счётчик для Round Robin распределения
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    // Флаг завершения пула
    private volatile boolean isShutdown = false;

    // Фабрика потоков
    private final CustomThreadFactory threadFactory;

    // Политика отказа
    private final RejectionHandler rejectionHandler;

    public CustomThreadPoolExecutor(int corePoolSize,
                                    int maxPoolSize,
                                    int queueSize,
                                    long keepAliveTime,
                                    TimeUnit timeUnit) {

        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueSize = queueSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;

        this.threadFactory = new CustomThreadFactory("МойПул");

        // Выбрана стратегия CallerRunsPolicy:
        // - задачи не теряются
        // - создаётся естественное ограничение нагрузки
        this.rejectionHandler = new CallerRunsPolicy();

        // Создаём базовые (core) потоки сразу
        for (int i = 0; i < corePoolSize; i++) {
            addWorker();
        }
    }

    /**
     * Создание нового рабочего потока.
     */
    private void addWorker() {

        if (workers.size() >= maxPoolSize) {
            return;
        }

        BlockingQueue<Runnable> queue =
                new ArrayBlockingQueue<>(queueSize);

        Worker worker = new Worker(queue);

        Thread thread = threadFactory.newThread(worker);
        worker.thread = thread;

        workers.add(worker);

        thread.start();
    }

    /**
     * Отправка задачи в пул.
     * Используется алгоритм Round Robin.
     */
    @Override
    public void execute(Runnable command) {

        if (command == null)
            throw new NullPointerException("Задача не может быть null");

        if (isShutdown) {
            rejectionHandler.reject(command, this);
            return;
        }

        int size = workers.size();

        if (size == 0) {
            rejectionHandler.reject(command, this);
            return;
        }

        int index = Math.abs(
                roundRobinIndex.getAndIncrement() % size
        );

        Worker worker = workers.get(index);

        if (!worker.queue.offer(command)) {

            PoolLogger.log("Пул",
                    "Очередь #" + index + " переполнена.");

            if (workers.size() < maxPoolSize) {

                PoolLogger.log("Пул",
                        "Создаём дополнительный поток из-за высокой нагрузки.");

                addWorker();
                execute(command);
                return;
            }

            rejectionHandler.reject(command, this);

        } else {
            PoolLogger.log("Пул",
                    "Задача принята в очередь #" + index);
        }
    }

    /**
     * Поддержка Callable через FutureTask.
     */
    @Override
    public <T> Future<T> submit(Callable<T> callable) {

        FutureTask<T> futureTask = new FutureTask<>(callable);

        execute(futureTask);

        return futureTask;
    }

    /**
     * Мягкая остановка:
     * - новые задачи не принимаются
     * - текущие завершаются
     */
    @Override
    public void shutdown() {
        PoolLogger.log("Пул", "Инициирована корректная остановка пула.");
        isShutdown = true;
    }

    /**
     * Жёсткая остановка:
     * - прерываем все потоки
     */
    @Override
    public void shutdownNow() {

        PoolLogger.log("Пул", "Инициирована немедленная остановка пула.");

        isShutdown = true;

        for (Worker worker : workers) {
            worker.thread.interrupt();
        }
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * Рабочий поток.
     *
     * - Обрабатывает задачи из своей очереди.
     * - Завершается по таймауту простоя.
     */
    private class Worker implements Runnable {

        private final BlockingQueue<Runnable> queue;
        private Thread thread;

        Worker(BlockingQueue<Runnable> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {

            try {

                while (true) {

                    // Если пул завершён и задач нет — выходим
                    if (isShutdown && queue.isEmpty()) {
                        break;
                    }

                    Runnable task =
                            queue.poll(keepAliveTime, timeUnit);

                    // Если задача не поступила за keepAliveTime
                    if (task == null) {

                        if (workers.size() > corePoolSize) {

                            PoolLogger.log("Рабочий поток",
                                    Thread.currentThread().getName()
                                            + " завершает работу по таймауту простоя.");

                            workers.remove(this);
                            break;
                        }

                        continue;
                    }

                    PoolLogger.log("Рабочий поток",
                            Thread.currentThread().getName()
                                    + " выполняет задачу.");

                    try {
                        task.run();
                    } catch (Exception e) {
                        PoolLogger.log("Ошибка",
                                "Во время выполнения задачи возникло исключение: "
                                        + e.getMessage());
                    }
                }

            } catch (InterruptedException ignored) {
                PoolLogger.log("Рабочий поток",
                        Thread.currentThread().getName()
                                + " был прерван.");
            }
        }
    }
}