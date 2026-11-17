package us.minitv.admin.flow.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import us.minitv.admin.flow.content.mapper.AFContentCascadeMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-mode transcode reconciler, ported from
 * {@code flow_router.start_transcode_reconciler}.
 *
 * <p>Bunny Stream has no library webhook configured, so finished encodes would
 * sit on {@code transcoding} until an operator opens the episode list. In fake
 * mode (the default, and what tests pin) this is a no-op like Python.</p>
 */
@Component
public class AFContentTranscodeReconciler {

    private static final Logger log = LoggerFactory.getLogger(AFContentTranscodeReconciler.class);
    private static final long INTERVAL_SECONDS = 120L;

    private final AFContentBunny bunny;
    private final AFContentCascadeMapper cascades;
    private final AFContentUploadService uploadService;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public AFContentTranscodeReconciler(AFContentBunny bunny,
                                        AFContentCascadeMapper cascades,
                                        AFContentUploadService uploadService) {
        this.bunny = bunny;
        this.cascades = cascades;
        this.uploadService = uploadService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!bunny.live() || !started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::loop, "af-content-transcode-reconcile");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!bunny.live()) {
                continue;
            }
            try {
                if (cascades.countByStatus("transcoding") == 0) {
                    continue;
                }
                Map<String, Object> result = uploadService.reconcileTranscoding(bunny.listStreamVideos());
                Object advanced = result.get("advanced");
                Object failed = result.get("failed");
                if (!Integer.valueOf(0).equals(advanced) || !Integer.valueOf(0).equals(failed)) {
                    log.info("transcode reconcile {}", result);
                }
            } catch (Exception exc) {
                log.error("transcode reconcile failed", exc);
            }
        }
    }
}
