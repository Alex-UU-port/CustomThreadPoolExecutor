package alexuuport;

public interface RejectionHandler {
    void reject(Runnable task, CustomThreadPoolExecutor executor);
}