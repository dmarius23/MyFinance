package ro.myfinance.common.jobs;

/**
 * Port for enqueuing async work. The web app produces; the worker process consumes.
 * Implementations must be safe to call within a DB transaction (the outbox pattern is used for
 * dispatch that must not be lost on crash).
 */
public interface JobQueue {

    void enqueue(Job job);
}
