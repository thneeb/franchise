package de.neebs.franchise.entity;

import java.util.concurrent.atomic.AtomicInteger;

public class LearningProgress {

    private final String runId;
    private final int gamesTotal;
    private final AtomicInteger gamesCompleted = new AtomicInteger();
    private volatile boolean done;

    public LearningProgress(String runId, int gamesTotal) {
        this.runId = runId;
        this.gamesTotal = gamesTotal;
    }

    public void increment() {
        int completed = gamesCompleted.incrementAndGet();
        if (completed >= gamesTotal) {
            done = true;
        }
    }

    public String getRunId() { return runId; }
    public int getGamesTotal() { return gamesTotal; }
    public int getGamesCompleted() { return gamesCompleted.get(); }
    public boolean isDone() { return done; }
}
