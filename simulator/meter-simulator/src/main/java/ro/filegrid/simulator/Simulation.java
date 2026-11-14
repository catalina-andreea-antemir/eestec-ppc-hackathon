package ro.filegrid.simulator;

import ro.filegrid.simulator.ReadingGenerator.Batch;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class Simulation {
    static final int MAX_IN_FLIGHT = 64;

    private final Profile profile;
    private final ReadingGenerator generator;
    private final BatchDelivery delivery;

    Simulation(Profile profile, BatchDelivery delivery) {
        this.profile = profile;
        this.generator = new ReadingGenerator(profile);
        this.delivery = delivery;
    }

    void run() throws InterruptedException {
        int workers = Math.min(profile.meters(), MAX_IN_FLIGHT);
        long deadline = System.nanoTime() + profile.duration().toNanos();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorCompletionService<Void> deliveries = new ExecutorCompletionService<>(executor);
            try {
                // Finish each round before starting another. Slow deliveries slow the simulation without
                // queuing missed rounds or moving a meter ahead of its pending batch.
                for (long round = 0; ; round++) {
                    long roundStart = System.nanoTime();
                    if (deadline - roundStart <= 0) {
                        return;
                    }
                    sendRound(round, deadline, workers, deliveries);
                    long now = System.nanoTime();
                    long nextRound = profile.sendingInterval().toNanos() - (now - roundStart);
                    long pause = Math.min(nextRound, deadline - now);
                    if (pause > 0) {
                        Thread.sleep(Duration.ofNanos(pause));
                    }
                }
            } catch (InterruptedException | RuntimeException failure) {
                executor.shutdownNow();
                throw failure;
            }
        }
    }

    private void sendRound(long round, long deadline, int workers, ExecutorCompletionService<Void> deliveries)
            throws InterruptedException {
        int active = 0;
        for (int index = 0; index < profile.meters(); index++) {
            if (generator.isSilent(index, round)) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            if (active == workers) {
                var completed = deliveries.poll(remaining, TimeUnit.NANOSECONDS);
                if (completed == null) {
                    break;
                }
                checkDelivery(completed);
                active--;
            }
            if (deadline - System.nanoTime() <= 0) {
                break;
            }

            int meterIndex = index;
            deliveries.submit(() -> {
                Batch batch = generator.generate(meterIndex, round);
                if (delivery.deliver(batch) && deadline - System.nanoTime() > 0
                        && generator.shouldDuplicate(meterIndex, round)) {
                    delivery.deliver(batch);
                }
                return null;
            });
            active++;
        }
        for (int i = 0; i < active; i++) {
            checkDelivery(deliveries.take());
        }
    }

    private static void checkDelivery(Future<Void> completed) throws InterruptedException {
        try {
            completed.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("Meter delivery failed", failed.getCause());
        }
    }
}
