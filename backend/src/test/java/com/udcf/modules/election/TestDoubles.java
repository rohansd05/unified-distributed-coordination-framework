package com.udcf.modules.election;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public class TestDoubles {

    public static class FakeTimer implements ElectionTimer {
        public final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable action, long delayMillis) {
            ScheduledTask task = new ScheduledTask(action, delayMillis, false);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        public void fire(long delayMillis) {
            for (ScheduledTask task : new ArrayList<>(tasks)) {
                if (task.delayMillis == delayMillis && !task.cancelled) {
                    task.action.run();
                }
            }
        }
    }

    public static class ScheduledTask {
        public final Runnable action;
        public final long delayMillis;
        public boolean cancelled;
        public ScheduledTask(Runnable action, long delayMillis, boolean cancelled) {
            this.action = action;
            this.delayMillis = delayMillis;
            this.cancelled = cancelled;
        }
    }

    public static class FakeClock extends Clock {
        public Instant instant = Instant.ofEpochSecond(1000);
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    public static class FakeNanoClock implements LongSupplier {
        public long nanos = 0;
        @Override public long getAsLong() { return nanos; }
    }

    public static class FakeMessenger implements ElectionMessenger {
        public final List<ElectionMessage> sent = new ArrayList<>();
        public final List<Integer> targets = new ArrayList<>();
        public boolean throwException = false;
        
        @Override public void send(int targetNodeId, ElectionMessage message) {
            if (throwException) {
                throw new RuntimeException("Simulated error");
            }
            targets.add(targetNodeId);
            sent.add(message);
        }
        public void clear() {
            sent.clear();
            targets.clear();
        }
    }

    public static class FakeEventListener implements ElectionEventListener {
        public final List<ElectionEvent> events = new ArrayList<>();
        @Override public void onEvent(ElectionEvent event) {
            events.add(event);
        }
        public void clear() {
            events.clear();
        }
    }
}
