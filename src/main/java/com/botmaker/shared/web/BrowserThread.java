// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <b>The one thread that talks to Playwright.</b> Playwright for Java is used from one thread only, and it hears its
 * browser only while that thread is inside one of its calls: every call goes through here, and between calls the
 * thread waits inside {@link BrowserContext#waitForCondition} on a context of its own, so a page's events (its
 * requests, its console, a route to judge) are heard while nobody calls.
 *
 * <p>A call from this thread itself, such as an event's handler calling back, runs at once.
 */
final class BrowserThread implements AutoCloseable {

    /** How long one wait for work lasts before it is started again. */
    private static final double PUMP_MS = 60_000;

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Thread thread;
    /** The context the thread waits on between calls; null until the browser runs, and once it closed. */
    private volatile BrowserContext pump;
    private volatile boolean closed;

    BrowserThread() {
        thread = Thread.ofPlatform().daemon().name("botmaker-playwright").start(this::loop);
    }

    /** Waits on {@code context} between calls from now on, so the browser's events are heard. */
    void pumpOn(BrowserContext context) {
        pump = context;
    }

    /** The browser gave no answer in time: a page that hangs, or the thread busy with one. */
    static final class TimedOut extends IllegalStateException {
        TimedOut(String message) {
            super(message);
        }
    }

    /**
     * Runs {@code work} on the thread and gives its answer, waiting at most {@code limit}; what it throws is thrown
     * here, and {@link TimedOut} when it took longer. From the thread itself it runs at once, unbounded.
     */
    <T> T call(Callable<T> work, Duration limit) {
        if (Thread.currentThread() == thread) {
            return call(work);
        }
        CompletableFuture<T> answer = submit(work);
        try {
            return answer.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TimedOut("no answer from the browser in " + limit.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the browser", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Runs {@code work} on the thread and gives its answer; what it throws is thrown here. */
    <T> T call(Callable<T> work) {
        if (Thread.currentThread() == thread) {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        CompletableFuture<T> answer = submit(work);
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the browser", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Runs {@code work} on the thread after what is queued, without waiting; in order with every other. Made from the
     * thread itself (an event's handler), it runs at once: the call the handler interrupts may be waiting for it, as a
     * navigation waits for its paused request to go on.
     */
    <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        if (closed) {
            answer.completeExceptionally(new IllegalStateException("the browser is closed"));
            return answer;
        }
        if (Thread.currentThread() == thread) {
            try {
                answer.complete(work.call());
            } catch (Throwable e) {
                answer.completeExceptionally(e);
            }
            return answer;
        }
        Task task = new Task(answer, () -> {
            try {
                answer.complete(work.call());
            } catch (Throwable e) {
                answer.completeExceptionally(e);
            }
        });
        tasks.add(task);
        // Closed while it was added, after the queue was emptied: nobody will run it.
        if (closed && tasks.remove(task)) {
            answer.completeExceptionally(new IllegalStateException("the browser is closed"));
        }
        return answer;
    }

    /** One queued call, with the answer a caller waits on. */
    private record Task(CompletableFuture<?> answer, Runnable work) implements Runnable {
        @Override
        public void run() {
            work.run();
        }
    }

    void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    private void loop() {
        while (!closed) {
            Runnable task = tasks.poll();
            if (task != null) {
                task.run();
                continue;
            }
            BrowserContext context = pump;
            try {
                if (context == null) {
                    task = tasks.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.run();
                    }
                } else {
                    context.waitForCondition(() -> closed || !tasks.isEmpty(),
                            new BrowserContext.WaitForConditionOptions().setTimeout(PUMP_MS));
                }
            } catch (InterruptedException e) {
                return;
            } catch (PlaywrightException timedOutOrThrown) {
                // A wait that ran out, or a handler's failure thrown out of it, is started again; only a browser
                // gone ends the waiting, a page closed under a handler does not.
                if (closed) {
                    return;
                }
                if (context.browser() == null || !context.browser().isConnected()) {
                    pump = null;
                }
            } catch (RuntimeException handler) {
                // A handler that threw is the page's trouble, not the thread's.
            }
        }
    }

    /** Ends the thread after {@code last}, which runs on it; what was queued after it is refused. */
    void closeAfter(Runnable last) {
        try {
            run(last);
        } finally {
            closed = true;
            pump = null;
            Runnable left;
            while ((left = tasks.poll()) != null) {
                // Refused, not run: no thread but this one may call Playwright.
                if (left instanceof Task task) {
                    task.answer().completeExceptionally(new IllegalStateException("the browser is closed"));
                }
            }
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        closeAfter(() -> {});
    }
}
