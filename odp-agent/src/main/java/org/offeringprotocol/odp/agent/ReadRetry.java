package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One retry budget for a read-only ODP operation, including its redirects. */
final class ReadRetry {
    private static final long BUDGET_MILLIS = 30_000;
    private static final int SINGLE_HEADER = 1;
    private static final DateTimeFormatter RFC850 =
            DateTimeFormatter.ofPattern("dd-MMM-uu HH:mm:ss zzz", Locale.ENGLISH);
    private static final DateTimeFormatter ASCTIME = DateTimeFormatter.ofPattern(
                    "EEE MMM d HH:mm:ss uuuu", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private final LongSupplier nanos;
    private final Supplier<Instant> clock;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;
    private final long started;
    private int retries;

    ReadRetry() {
        this(
                System::nanoTime,
                Instant::now,
                Thread::sleep,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    ReadRetry(LongSupplier nanos, Supplier<Instant> clock, Sleeper sleeper, DoubleSupplier jitter) {
        this.nanos = nanos;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.started = nanos.getAsLong();
    }

    HttpResponse<byte[]> send(OdpTransport transport, HttpRequest request, int maximumBytes)
            throws IOException, InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("ODP request was interrupted");
            }
            HttpResponse<byte[]> response = transport.send(request, maximumBytes);
            int status = response.statusCode();
            if ((status != 429 && status != 503) || retries == 3) {
                return response;
            }
            long delay = delay(response);
            long elapsed = (nanos.getAsLong() - started) / 1_000_000;
            if (delay < 0 || elapsed >= BUDGET_MILLIS || delay > BUDGET_MILLIS - elapsed) {
                return response;
            }
            sleeper.sleep(delay);
            // Scheduling delays must not turn a permitted wait into an unbounded retry window.
            if ((nanos.getAsLong() - started) / 1_000_000 > BUDGET_MILLIS) {
                return response;
            }
            retries++;
        }
    }

    private long delay(HttpResponse<byte[]> response) {
        var values = response.headers().allValues("Retry-After");
        if (values.isEmpty()) {
            return response.statusCode() == 503 ? (long) (1000L * (1L << retries) * jitter.getAsDouble()) : -1;
        }
        if (values.size() != SINGLE_HEADER) {
            return -1;
        }
        String value = values.get(0).strip();
        try {
            if (value.matches("[0-9]+")) {
                return Math.multiplyExact(Long.parseLong(value), 1000L);
            }
            Instant target = httpDate(value);
            long milliseconds =
                    Math.subtractExact(target.toEpochMilli(), clock.get().toEpochMilli());
            return Math.max(0, milliseconds);
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException exception) {
            return -1;
        }
    }

    private Instant httpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException exception) {
            if (value.matches("[A-Za-z]+, [0-9]{2}-[A-Za-z]{3}-[0-9]{2} .*")) {
                ZonedDateTime date = ZonedDateTime.parse(value.substring(value.indexOf(',') + 2), RFC850);
                if (date.toInstant()
                        .isAfter(
                                clock.get().atZone(ZoneOffset.UTC).plusYears(50).toInstant())) {
                    date = date.minusYears(100);
                }
                return date.toInstant();
            }
            return Instant.from(ASCTIME.parse(value.replaceAll(" +", " ")));
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }
}
