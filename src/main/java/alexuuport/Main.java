package alexuuport;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {

            CustomThreadPoolExecutor ex = new CustomThreadPoolExecutor(2,
                    4,
                    10,
                    2,
                    5 ,
                    TimeUnit.SECONDS);

        for (int i = 0; i < 10; i++) {
            int taskId = i;

            ex.execute(() -> {
                System.out.println(Thread.currentThread().getName() +
                        " START task " + taskId);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    System.out.println("Interrupted " + taskId);
                }

                System.out.println(Thread.currentThread().getName() +
                        " END task " + taskId);
            });
        }

        ex.shutdown();
    }
}