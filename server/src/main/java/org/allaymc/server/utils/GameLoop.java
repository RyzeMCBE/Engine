package org.allaymc.server.utils;

import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * GameLoop is a simple game loop implementation.
 *
 * @author daoge_cmd
 */
@Slf4j
public final class GameLoop {
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final Runnable onStart;
    private final Consumer<GameLoop> onTick;
    private final Runnable onIdle;
    private final Runnable onStop;
    private volatile Thread loopThread;
    @Getter
    private final int loopCountPerSec;
    private final float[] tickSummary;
    private final float[] MSPTSummary;
    @Getter
    private long tick;

    private GameLoop(Runnable onStart, Consumer<GameLoop> onTick, Runnable onIdle, Runnable onStop, int loopCountPerSec, long currentTick) {
        if (loopCountPerSec <= 0) {
            throw new IllegalArgumentException("Loop count per second must be greater than 0! (loopCountPerSec=" + loopCountPerSec + ")");
        }
        this.onStart = onStart;
        this.onTick = onTick;
        this.onIdle = onIdle;
        this.onStop = onStop;
        this.loopCountPerSec = loopCountPerSec;
        this.tick = currentTick;
        // Sample window size = loopCountPerSec, so we always sample 1 second of data
        this.tickSummary = new float[loopCountPerSec];
        this.MSPTSummary = new float[loopCountPerSec];
        Arrays.fill(tickSummary, 1000f / loopCountPerSec);
        Arrays.fill(MSPTSummary, 0f);
    }

    public static GameLoopBuilder builder() {
        return new GameLoopBuilder();
    }

    public float getTickUsage() {
        return getMSPT() / (1000f / loopCountPerSec);
    }

    /**
     * Measured wall-clock TPS. Each sample is the interval between two actual
     * tick starts, including time spent processing network packets in onIdle.
     * Averaging intervals (not their reciprocals) correctly reflects stalls.
     */
    public float getTPS() {
        float sumMillis = 0;
        for (float intervalMillis : tickSummary) {
            sumMillis += intervalMillis;
        }
        return Math.min(loopCountPerSec, tickSummary.length * 1000f / Math.max(sumMillis, 0.001f));
    }

    /** Peak work time in the current one-second sample window. */
    public float getPeakMSPT() {
        float peak = 0;
        for (float value : MSPTSummary) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    /** Largest wall-clock gap between consecutive ticks in the sample window. */
    public float getMaxTickIntervalMS() {
        float peak = 0;
        for (float value : tickSummary) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    public float getMSPT() {
        float sum = 0;
        int count = MSPTSummary.length;
        for (float mspt : MSPTSummary) {
            sum += mspt;
        }
        return sum / count;
    }

    /**
     * Wake up the game loop from idle sleep.
     * This can be called from any thread to interrupt the sleep and trigger the onIdle callback.
     */
    public void wakeUp() {
        var thread = loopThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    public void startLoop() {
        loopThread = Thread.currentThread();
        onStart.run();
        long nanoSleepTime = 0;
        long idealNanoSleepPerTick = 1000000000 / loopCountPerSec;
        long previousTickStart = -1;
        long idleWorkNanos = 0;
        while (isRunning.get()) {
            // Figure out how long it took to tick
            long startTickTime = System.nanoTime();
            long tickIntervalNanos = previousTickStart < 0
                    ? idealNanoSleepPerTick : Math.max(1, startTickTime - previousTickStart);
            previousTickStart = startTickTime;
            onTick.accept(this);
            tick++;
            long timeTakenToTick = System.nanoTime() - startTickTime;
            // Include the actual onIdle work accumulated since the previous tick.
            // Do not include idle parking/sleep in MSPT; it is reflected by TPS.
            updateMSPT(timeTakenToTick + idleWorkNanos, MSPTSummary);
            idleWorkNanos = 0;
            updateTPS(tickIntervalNanos);

            long sumOperateTime = System.nanoTime() - startTickTime;
            // Sleep for the ideal time but take into account the time spent running the tick
            nanoSleepTime += idealNanoSleepPerTick - sumOperateTime;
            // Use LockSupport.parkNanos for event-driven wake up
            while (nanoSleepTime > 0 && isRunning.get()) {
                long sleepStart = System.nanoTime();
                LockSupport.parkNanos(nanoSleepTime);
                // Call onIdle callback when woken up (e.g., to process network packets)
                if (onIdle != null && isRunning.get()) {
                    long idleStart = System.nanoTime();
                    try {
                        onIdle.run();
                    } finally {
                        idleWorkNanos += System.nanoTime() - idleStart;
                    }
                }
                // Subtract total elapsed time (park + onIdle execution)
                nanoSleepTime -= System.nanoTime() - sleepStart;
            }
        }
        loopThread = null;
        onStop.run();
    }

    private void updateTPS(long wallClockIntervalNanos) {
        System.arraycopy(tickSummary, 1, tickSummary, 0, tickSummary.length - 1);
        tickSummary[tickSummary.length - 1] = wallClockIntervalNanos / 1000000f;
    }

    private void updateMSPT(float timeTakenToTick, float[] msptSummary) {
        System.arraycopy(msptSummary, 1, msptSummary, 0, msptSummary.length - 1);
        msptSummary[msptSummary.length - 1] = timeTakenToTick / 1000000f;
    }

    public void stop() {
        isRunning.set(false);
        wakeUp(); // Wake up the thread if it's parked so it can exit immediately
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public static class GameLoopBuilder {
        private Runnable onStart = () -> {
        };
        private Consumer<GameLoop> onTick = gameLoop -> {
        };
        private Runnable onIdle;
        private Runnable onStop = () -> {
        };
        private int loopCountPerSec = 20;
        private long currentTick = 0;

        public GameLoopBuilder onStart(Runnable onStart) {
            this.onStart = onStart;
            return this;
        }

        public GameLoopBuilder onTick(Consumer<GameLoop> onTick) {
            this.onTick = onTick;
            return this;
        }

        /**
         * Set the callback to be called when the game loop is woken up during idle sleep.
         * This is useful for processing events (like network packets) between ticks.
         *
         * @param onIdle the callback to run when woken up
         * @return this builder
         */
        public GameLoopBuilder onIdle(Runnable onIdle) {
            this.onIdle = onIdle;
            return this;
        }

        public GameLoopBuilder onStop(Runnable onStop) {
            this.onStop = onStop;
            return this;
        }

        public GameLoopBuilder loopCountPerSec(int loopCountPerSec) {
            Preconditions.checkArgument(loopCountPerSec > 0 && loopCountPerSec <= 1024);
            this.loopCountPerSec = loopCountPerSec;
            return this;
        }

        public GameLoopBuilder currentTick(long currentTick) {
            this.currentTick = currentTick;
            return this;
        }

        public GameLoop build() {
            return new GameLoop(onStart, onTick, onIdle, onStop, loopCountPerSec, currentTick);
        }
    }
}
