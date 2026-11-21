package com.mewcode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessExecution {
    private static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;

    private ProcessExecution() {}

    public record Result(int exitCode, String output) {}

    public static Result capture(ProcessBuilder builder, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = builder.redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            var output = new FutureTask<>(() -> {
                try (var stream = process.getInputStream()) {
                    var data = stream.readNBytes(MAX_CAPTURE_BYTES + 1);
                    if (data.length > MAX_CAPTURE_BYTES) throw new IOException("Process output exceeds the capture limit");
                    return new String(data, StandardCharsets.UTF_8);
                }
            });
            Thread.startVirtualThread(output);
            String text = output.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (!process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            return new Result(process.exitValue(), text);
        } catch (TimeoutException e) {
            throw new IOException("Process timed out after " + timeout.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            throw new IOException("Could not capture process output", e.getCause());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
