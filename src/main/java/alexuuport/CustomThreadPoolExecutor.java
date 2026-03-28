package alexuuport;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Кастомная реализация пула потоков.
 * Поддерживает:
 *  - execute(Runnable)
 *  - submit(Callable)
 *  - ограниченную очередь
 *  - keepAliveTime
 *  - minSpareThreads (кастомная логика)
 */
public class CustomThreadPoolExecutor implements CustomExecutor {

    // --- Конфигурация пула ---

    // минимальное количество потоков (ядро)
    private final int corePoolSize;

    // максимальное количество потоков
    private final int maxPoolSize;

    // минимальное количество свободных потоков (кастомная фича)
    private final int minSpareThreads;

    // время жизни неактивного потока
    private final long keepAliveTime;

    // единицы измерения времени
    private final TimeUnit timeUnit;

    // очередь задач (ограниченная)
    private final BlockingQueue<Runnable> taskQueue;

    // множество всех воркеров (нужно для управления и shutdown)
    private final Set<Worker> workers = ConcurrentHashMap.newKeySet();

    // общее количество потоков
    private final AtomicInteger totalThreads = new AtomicInteger(0);

    // количество простаивающих потоков
    private final AtomicInteger idleThreads = new AtomicInteger(0);

    // флаг остановки пула
    private volatile boolean isShutdown = false;

    public CustomThreadPoolExecutor(
            int corePoolSize,
            int maxPoolSize,
            int queueSize,
            int minSpareThreads,
            long keepAliveTime,
            TimeUnit timeUnit
    ) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.minSpareThreads = minSpareThreads;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;

        // ограниченная потокобезопасная очередь
        this.taskQueue = new ArrayBlockingQueue<>(queueSize);

        // создаём core-потоки сразу
        for (int i = 0; i < corePoolSize; i++) {
            addWorker();
        }
    }

    /**
     * Основной метод для запуска Runnable задач
     */
    @Override
    public void execute(Runnable command) {

        // если пул остановлен — новые задачи не принимаем
        if (isShutdown) {
            throw new RejectedExecutionException("Executor is shutdown");
        }


        log("Submitting task");
        // пробуем положить задачу в очередь
        if (!taskQueue.offer(command)) {

            log("Очередь заполнена, попытка создать worker");
            // если очередь заполнена — пробуем создать новый поток
            if (totalThreads.get() < maxPoolSize) {
                addWorker();

                // повторная попытка добавить задачу
                if (!taskQueue.offer(command)) {
                    throw new RejectedExecutionException("Queue is full");
                }
            } else {
                // если достигли maxPoolSize — отклоняем задачу
                throw new RejectedExecutionException("Max threads reached");
            }
        }

        // после добавления задачи проверяем minSpareThreads
        adjustSpareThreads();
    }

    /**
     * submit оборачивает Callable в FutureTask
     */
    @Override
    public <T> Future<T> submit(Callable<T> callable) {

        // FutureTask реализует и Runnable, и Future
        FutureTask<T> futureTask = new FutureTask<>(callable);

        // отправляем как обычную задачу
        execute(futureTask);

        return futureTask;
    }

    /**
     * Мягкая остановка:
     * - новые задачи не принимаем
     * - текущие продолжают выполняться
     */
    @Override
    public void shutdown() {
        isShutdown = true;
        log("Shutdown initiated");
    }

    /**
     * Жёсткая остановка:
     * - прерываем все потоки
     * - очищаем очередь
     */
    @Override
    public void shutdownNow() {
        isShutdown = true;

        // прерываем все воркеры
        for (Worker worker : workers) {
            worker.interrupt();
        }
        log("Shutdown NOW initiated");
        // удаляем все ожидающие задачи
        taskQueue.clear();
    }

    /**
     * Добавляет новый поток в пул
     */
    private void addWorker() {

        // защита от превышения лимита
        if (totalThreads.get() >= maxPoolSize) return;

        log("Creating new worker");
        Worker worker = new Worker();

        workers.add(worker);

        // увеличиваем счётчики
        totalThreads.incrementAndGet();
        idleThreads.incrementAndGet();

        worker.start();
    }

    /**
     * Обеспечивает наличие минимального количества свободных потоков
     */
    private void adjustSpareThreads() {

        // если свободных потоков меньше, чем нужно —
        // создаём новые (даже без высокой нагрузки)
        while (idleThreads.get() < minSpareThreads
                && totalThreads.get() < maxPoolSize) {

            addWorker();
        }
    }

    /**
     * Получение задачи для выполнения
     */
    private Runnable getTask() {

        try {
            // если поток "сверх core" — он может умереть по таймауту
            if (totalThreads.get() > corePoolSize) {
                return taskQueue.poll(keepAliveTime, timeUnit);
            } else {
                log("Waiting for task...");
                // core-потоки живут всегда и ждут задачу
                return taskQueue.take();
            }
        } catch (InterruptedException e) {
            // если поток прервали — завершаем его
            return null;
        }
    }

    private void log(String message) {
        System.out.printf(
                "[%s] [threads=%d idle=%d queue=%d] %s%n",
                Thread.currentThread().getName(),
                totalThreads.get(),
                idleThreads.get(),
                taskQueue.size(),
                message
        );
    }

    /**
     * Внутренний класс рабочего потока
     */
    private class Worker extends Thread {

        @Override
        public void run() {
            try {
                while (true) {


                    log("Worker started");
                    // берём задачу
                    Runnable task = getTask();

                    // если null — поток должен завершиться
                    if (task == null) {
                        log("Worker exiting (no task / timeout / interrupt)");
                        return;
                    }

                    // поток перестаёт быть idle
                    idleThreads.decrementAndGet();

                    try {
                        log("Task started");
                        // выполняем задачу
                        task.run();
                        log("Task finished");
                    } finally {
                        // после выполнения снова становится idle
                        idleThreads.incrementAndGet();
                    }
                }
            } finally {
                // при завершении потока уменьшаем счётчики
                totalThreads.decrementAndGet();
                workers.remove(this);
            }
        }
    }
}