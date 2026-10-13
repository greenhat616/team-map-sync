package teammapsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Background threads that keep compression and file IO off the game and server threads. */
public final class Workers {

    private Workers() {
    }

    /** One low-priority daemon thread; tasks run in submission order, which the stores rely on. */
    public static ExecutorService single(String name) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });
    }
}
