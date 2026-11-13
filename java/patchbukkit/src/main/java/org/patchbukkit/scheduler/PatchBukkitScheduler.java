package org.patchbukkit.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.jetbrains.annotations.NotNull;

public class PatchBukkitScheduler implements BukkitScheduler {

    private static final long MS_PER_TICK = 50L;

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "patchbukkit-scheduler");
                t.setDaemon(true);
                return t;
            }
    );

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<Integer, Plugin> owners = new ConcurrentHashMap<>();

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("PatchBukkitScheduler");

    /** Sync tasks due this tick, handed over from the scheduler threads to the server tick. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> SYNC_QUEUE =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_SYNC_TASKS_PER_TICK = 4096;

    /**
     * Runs the sync tasks that came due since the last tick. The host calls this once per server
     * tick, so plugin code that expects the main thread keeps running on the server's tick.
     */
    /** Queues work for the next sync drain; queued from inside a drain, it runs in that same drain. */
    public static void runOnNextDrain(Runnable work) {
        SYNC_QUEUE.add(work);
    }

    public static void drainSyncTasks() {
        for (int i = 0; i < MAX_SYNC_TASKS_PER_TICK; i++) {
            Runnable task = SYNC_QUEUE.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
    }

    private BukkitTask submit(Plugin plugin, Runnable work, long delayTicks, long periodTicks) {
        return schedule(plugin, work, delayTicks, periodTicks, true);
    }

    private BukkitTask submitAsync(Plugin plugin, Runnable work, long delayTicks, long periodTicks) {
        return schedule(plugin, work, delayTicks, periodTicks, false);
    }

    private BukkitTask schedule(Plugin plugin, Runnable work, long delayTicks, long periodTicks, boolean sync) {
        int id = nextId.getAndIncrement();
        long delayMs = Math.max(0, delayTicks) * MS_PER_TICK;
        // The executor keeps a failed task's exception inside its Future, where nothing reads it,
        // so a plugin task that throws would fail silently. Log it instead.
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE,
                    "[PatchBukkit] Scheduled task of plugin " + plugin.getName() + " threw an exception", t);
            }
        };
        // Sync tasks run on the server tick (drainSyncTasks); only the timing happens here.
        Runnable task = sync ? () -> SYNC_QUEUE.add(guarded) : guarded;
        ScheduledFuture<?> future;
        if (periodTicks > 0) {
            future = executor.scheduleAtFixedRate(task, delayMs, periodTicks * MS_PER_TICK, TimeUnit.MILLISECONDS);
        } else {
            future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
        futures.put(id, future);
        owners.put(id, plugin);
        return new PatchBukkitTask(id, plugin, false, this);
    }

    @Override
    public void cancelTask(int taskId) {
        ScheduledFuture<?> f = futures.remove(taskId);
        if (f != null) f.cancel(false);
        owners.remove(taskId);
    }

    @Override
    public void cancelTasks(@NotNull Plugin plugin) {
        owners.entrySet().removeIf(e -> {
            if (e.getValue().equals(plugin)) {
                ScheduledFuture<?> f = futures.remove(e.getKey());
                if (f != null) f.cancel(false);
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean isCurrentlyRunning(int taskId) {
        return futures.containsKey(taskId);
    }

    @Override
    public boolean isQueued(int taskId) {
        ScheduledFuture<?> f = futures.get(taskId);
        return f != null && !f.isDone();
    }

    @Override
    public @NotNull List<BukkitWorker> getActiveWorkers() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<BukkitTask> getPendingTasks() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull BukkitTask runTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return submit(plugin, task, 0, 0);
    }

    @Override
    public void runTask(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submit(plugin, () -> task.accept(ref[0]), 0, 0);
    }

    @Override
    public @NotNull BukkitTask runTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return submit(plugin, task, 0, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task) {
        return submitAsync(plugin, task, 0, 0);
    }

    @Override
    public void runTaskAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submitAsync(plugin, () -> task.accept(ref[0]), 0, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return submitAsync(plugin, task, 0, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskLater(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return submit(plugin, task, delay, 0);
    }

    @Override
    public void runTaskLater(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submit(plugin, () -> task.accept(ref[0]), delay, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskLater(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return submit(plugin, task, delay, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return submitAsync(plugin, task, delay, 0);
    }

    @Override
    public void runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submitAsync(plugin, () -> task.accept(ref[0]), delay, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return submitAsync(plugin, task, delay, 0);
    }

    @Override
    public @NotNull BukkitTask runTaskTimer(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return submit(plugin, task, delay, period);
    }

    @Override
    public void runTaskTimer(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay, long period) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submit(plugin, () -> task.accept(ref[0]), delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimer(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return submit(plugin, task, delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return submitAsync(plugin, task, delay, period);
    }

    @Override
    public void runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay, long period) {
        BukkitTask[] ref = new BukkitTask[1];
        ref[0] = submitAsync(plugin, () -> task.accept(ref[0]), delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return submitAsync(plugin, task, delay, period);
    }

    @Override
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return submit(plugin, task, delay, 0).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return submit(plugin, task, delay, 0).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return submit(plugin, task, 0, 0).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return submit(plugin, task, 0, 0).getTaskId();
    }

    @Override
    public int scheduleSyncRepeatingTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return submit(plugin, task, delay, period).getTaskId();
    }

    @Override
    public int scheduleSyncRepeatingTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return submit(plugin, task, delay, period).getTaskId();
    }

    @Override
    public int scheduleAsyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return submit(plugin, task, delay, 0).getTaskId();
    }

    @Override
    public int scheduleAsyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return submit(plugin, task, 0, 0).getTaskId();
    }

    @Override
    public int scheduleAsyncRepeatingTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return submit(plugin, task, delay, period).getTaskId();
    }

    @Override
    public <T> @NotNull Future<T> callSyncMethod(@NotNull Plugin plugin, @NotNull Callable<T> task) {
        return executor.submit(task);
    }

    @Override
    public @NotNull Executor getMainThreadExecutor(@NotNull Plugin plugin) {
        return executor;
    }
}
