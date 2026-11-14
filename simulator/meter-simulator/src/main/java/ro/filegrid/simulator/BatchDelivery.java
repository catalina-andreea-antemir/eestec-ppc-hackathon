package ro.filegrid.simulator;

import ro.filegrid.simulator.FileGridClient.AuthFailure;
import ro.filegrid.simulator.FileGridClient.Tokens;
import ro.filegrid.simulator.FileGridClient.TransientTransportFailure;
import ro.filegrid.simulator.ReadingGenerator.Batch;

import java.io.IOException;
import java.util.Optional;

final class BatchDelivery {
    private static final System.Logger LOG = System.getLogger(BatchDelivery.class.getName());

    private final FileGridClient client;
    private final RetryPolicy retry;
    private final SimulatorMetrics metrics;
    private final String scenario;

    BatchDelivery(FileGridClient client, RetryPolicy retry, SimulatorMetrics metrics, String scenario) {
        this.client = client;
        this.retry = retry;
        this.metrics = metrics;
        this.scenario = scenario;
    }

    void login() throws IOException, InterruptedException {
        long deadline = retry.deadline();
        for (int i = 0; ; i++) {
            try {
                client.authenticate(null, deadline);
                return;
            } catch (AuthFailure failure) {
                if (!RetryPolicy.isRetryable(failure.status) || !retry.pause(i, failure.retryAfter, deadline)) {
                    throw failure;
                }
            }
        }
    }

    boolean deliver(Batch batch) throws InterruptedException {
        long deadline = retry.deadline();
        metrics.pending.addAndGet(batch.count());
        int[] attempts = {0};
        Runnable countAttempt = () -> {
            if (attempts[0]++ == 0) {
                metrics.sent.addAndGet(batch.count());
            } else {
                metrics.retried.incrementAndGet();
            }
        };
        Tokens rejected = null;
        boolean reauthenticated = false;
        boolean confirmed = false;
        try {
            for (int retryIndex = 0; !retry.remaining(deadline).isZero(); ) {
                Optional<String> retryAfter;
                try {
                    Tokens auth = client.authenticate(rejected, deadline);
                    var response = client.send(batch, scenario, auth, deadline, countAttempt);
                    int status = response.statusCode();
                    if (status == 200 || status == 201) {
                        metrics.confirmed.addAndGet(batch.count());
                        confirmed = true;
                        return true;
                    }
                    if (status == 401 && !reauthenticated) {
                        rejected = auth;
                        reauthenticated = true;
                        continue;
                    }
                    if (!RetryPolicy.isRetryable(status)) {
                        String reason = status == 409 ? "HTTP 409 BATCH_OVERLAP" : "HTTP " + status;
                        return rejected(batch, reason);
                    }
                    retryAfter = response.headers().firstValue("Retry-After");
                } catch (AuthFailure failure) {
                    if (!RetryPolicy.isRetryable(failure.status)) {
                        return rejected(batch, failure.getMessage());
                    }
                    retryAfter = failure.retryAfter;
                } catch (TransientTransportFailure failure) {
                    retryAfter = Optional.empty();
                } catch (IOException failure) {
                    String reason = retry.remaining(deadline).isZero() ? "retry deadline reached" : failure.getMessage();
                    return rejected(batch, reason);
                }
                if (!retry.pause(retryIndex++, retryAfter, deadline)) {
                    break;
                }
            }
            return rejected(batch, "retry deadline reached");
        } finally {
            if (!confirmed) {
                metrics.abandoned.addAndGet(batch.count());
            }
            metrics.pending.addAndGet(-batch.count());
        }
    }

    private static boolean rejected(Batch batch, String reason) {
        LOG.log(System.Logger.Level.WARNING, "Batch {0} ended without confirmation: {1}",
                batch.idempotencyKey(), reason);
        return false;
    }
}
