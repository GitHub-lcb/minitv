package us.minitv.psocial;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process wrapper for the moderation queue.
 *
 * <p>Two jobs, both ported from {@code moderation.py}/{@code main.py}:</p>
 * <ul>
 *   <li>{@link #submitProcess(long)} mimics FastAPI BackgroundTasks — a comment
 *       is moderated right after the POST response is produced.</li>
 *   <li>A daemon sweeper retries queued rows and running rows whose worker died
 *       every 30s ({@code MODERATION_WORKER=0} disables it, like Python).</li>
 * </ul>
 */
@Component
public class PSocialModerationWorker {

    private static final Logger log = LoggerFactory.getLogger(PSocialModerationWorker.class);
    private static final int WORKER_INTERVAL_SECONDS = 30;
    private static final int BATCH_LIMIT = 10;

    private final PSocialModerationService moderation;
    private final Environment env;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Thread sweeper;

    public PSocialModerationWorker(PSocialModerationService moderation, Environment env) {
        this.moderation = moderation;
        this.env = env;
    }

    @PostConstruct
    public void start() {
        String flag = env.getProperty("MODERATION_WORKER", "1");
        if (flag != null && "0".equals(flag.strip())) {
            return;
        }
        Thread thread = new Thread(this::loop, "minitv-moderation-worker");
        thread.setDaemon(true);
        sweeper = thread;
        thread.start();
    }

    private void loop() {
        while (true) {
            try {
                moderation.processPendingBatch(BATCH_LIMIT);
            } catch (Throwable ex) {
                // One bad row must not kill the worker.
                log.warn("moderation worker iteration failed", ex);
            }
            try {
                Thread.sleep(WORKER_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Queue one comment for post-response moderation (FastAPI BackgroundTasks). */
    public void submitProcess(long commentId) {
        executor.execute(() -> {
            try {
                moderation.processComment(commentId);
            } catch (Throwable ex) {
                log.warn("post-response moderation failed (comment={})", commentId, ex);
            }
        });
    }

    @PreDestroy
    public void stop() {
        Thread thread = sweeper;
        if (thread != null) {
            thread.interrupt();
        }
        executor.shutdownNow();
    }
}
