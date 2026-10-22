package io.github.sanskar121543.reconciliation.dashboard;

import io.github.sanskar121543.reconciliation.run.Run;
import io.github.sanskar121543.reconciliation.run.RunRepository;
import io.github.sanskar121543.reconciliation.run.RunResponse;
import io.github.sanskar121543.reconciliation.run.RunSummaries;
import io.github.sanskar121543.reconciliation.run.RunSummary;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-Sent Events for the dashboard. Each instance reads the latest runs from Postgres once a second while
 * anyone is connected, and sends the snapshot only when it changed. Reading the database, not listening for
 * changes inside this instance, is what makes it right with two instances: a dashboard connected to app1 sees
 * the lines app2 stores and the runs app2 completes.
 */
@Component
public class DashboardStream implements SmartLifecycle {

    static final int RUNS = 10;
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(DashboardStream.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final RunRepository runs;
    private final RunSummaries summaries;
    private final JsonMapper jsonMapper;
    private volatile String lastSent;
    private volatile boolean running;

    public DashboardStream(RunRepository runs, RunSummaries summaries, JsonMapper jsonMapper) {
        this.runs = runs;
        this.summaries = summaries;
        this.jsonMapper = jsonMapper;
    }

    /** The current snapshot goes out at once; later ones as they change. The browser reconnects after TIMEOUT. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, snapshot());
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    void publishChanges() {
        if (emitters.isEmpty()) {
            lastSent = null;
            return;
        }
        String snapshot;
        try {
            snapshot = snapshot();
        } catch (DataAccessException e) {
            // The database is unreachable: say nothing rather than something wrong, and try again next tick.
            log.debug("Dashboard snapshot skipped: {}", e.getMessage());
            return;
        }
        if (!snapshot.equals(lastSent)) {
            lastSent = snapshot;
            emitters.forEach(emitter -> send(emitter, snapshot));
        }
    }

    /** Proxies close a connection that has been silent too long; a comment line keeps it open. */
    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException gone) {
                emitters.remove(emitter);
            }
        }
    }

    int subscribers() {
        return emitters.size();
    }

    private String snapshot() {
        List<Run> recent = runs.findAllByOrderByIdDesc(Limit.of(RUNS));
        Map<Long, RunSummary> byId = summaries.forRuns(recent.stream().map(Run::getId).toList());
        List<RunResponse> views = recent.stream().map(run -> RunResponse.of(run, byId.get(run.getId()))).toList();
        return jsonMapper.writeValueAsString(Map.of("runs", views));
    }

    private void send(SseEmitter emitter, String snapshot) {
        try {
            emitter.send(SseEmitter.event().name("runs").data(snapshot));
        } catch (IOException | IllegalStateException gone) {
            // The browser went away (IOException) or the emitter already completed (IllegalStateException).
            emitters.remove(emitter);
        }
    }

    // Graceful shutdown waits for open requests, and an event stream never ends by itself: close the streams
    // first (this phase stops before the web server's), so the instance stops in seconds and browsers reconnect
    // to the other one.
    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
