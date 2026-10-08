package com.transferchain.httptests;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.transferchain.http.UltraHTTP;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.cordova.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real plugin dispatch, not a second implementation of its concurrency policy. */
@RunWith(AndroidJUnit4.class)
public final class HTTPParallelTest {
    private static final class Capture extends CallbackContext {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger();
        volatile PluginResult result;

        Capture() { super("parallel-test", null); }

        @Override public void sendPluginResult(PluginResult value) {
            result = value;
            count.incrementAndGet();
            done.countDown();
        }

        void await() throws Exception {
            assertTrue("Callback did not finish", done.await(40, TimeUnit.SECONDS));
            assertEquals("More than one terminal callback", 1, count.get());
        }

        void code(int code) throws Exception {
            await();
            assertEquals(PluginResult.Status.ERROR.ordinal(), result.getStatus());
            assertEquals(code, new JSONObject(result.getMessage()).getInt("code"));
        }
    }

    private static final class Harness implements AutoCloseable {
        final ExecutorService pool;
        final UltraHTTP plugin = new UltraHTTP();

        Harness(ExecutorService pool) {
            this.pool = pool;
            CordovaInterface cordova = (CordovaInterface) Proxy.newProxyInstance(
                    HTTPParallelTest.class.getClassLoader(), new Class<?>[]{CordovaInterface.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("getThreadPool")) return pool;
                        if (method.getName().equals("getContext")) return InstrumentationRegistry.getInstrumentation().getTargetContext();
                        return null;
                    });

            plugin.privateInitialize("http-parallel", cordova, null, new CordovaPreferences());
        }

        Capture request(String id, String url, byte[] body, boolean pinned) throws Exception {
            JSONObject options = new JSONObject().put("id", id).put("url", url)
                    .put("method", body.length == 0 ? "GET" : "POST")
                    .put("headers", new JSONObject()).put("hasBody", body.length != 0)
                    .put("fingerprints", pinned ? new JSONArray().put("certificate:" + "00".repeat(32)) : new JSONArray());
            Capture capture = new Capture();

            assertTrue(plugin.execute("request", new CordovaArgs(new JSONArray().put(options)
                    .put(android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))), capture));
            return capture;
        }

        void cancel(String id) throws Exception {
            Capture capture = new Capture();

            plugin.execute("cancel", new CordovaArgs(new JSONArray().put(id)), capture);
            capture.await();
        }

        // Reflection is confined to instrumentation. No diagnostic/secret access
        // API is added to production: inspect actual registry and owned buffers.
        Map<?, ?> jobs() throws Exception {
            Field field = UltraHTTP.class.getDeclaredField("jobs");

            field.setAccessible(true);
            return (Map<?, ?>) field.get(plugin);
        }

        List<byte[]> ownedBodies() throws Exception {
            List<byte[]> buffers = new ArrayList<>();

            for (Object job : jobs().values()) {
                Field body = job.getClass().getDeclaredField("body");

                body.setAccessible(true);
                buffers.add((byte[]) body.get(job));
            }

            return buffers;
        }

        void drained(int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

            while (!jobs().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("Cancelled/native jobs were not released promptly", 0, jobs().size());
        }

        @Override public void close() throws Exception {
            plugin.onDestroy();
            Field cleanup = UltraHTTP.class.getDeclaredField("cancellations");

            cleanup.setAccessible(true);
            ExecutorService cleanupPool = (ExecutorService) cleanup.get(plugin);

            assertTrue("Cleanup executor leaked after destroy", cleanupPool.awaitTermination(5, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue("Native executor did not drain", pool.awaitTermination(40, TimeUnit.SECONDS));
            drained(1);
        }
    }

    @Test
    public void overlappingTLSRequestsKeepPinsAndCallbacksIsolated() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override protected void beforeExecute(Thread thread, Runnable task) {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                ready.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            @Override protected void afterExecute(Runnable task, Throwable failure) { running.decrementAndGet(); }
        };

        try (Harness harness = new Harness(pool)) {
            for (int round = 0; round < 3; round++) {
                List<Capture> captures = new ArrayList<>();
                List<String> urls = new ArrayList<>();

                for (int index = 0; index < 8; index++) {
                    String url = "https://example.org/?http-parallel=" + round + "-" + index;

                    urls.add(url);
                    captures.add(harness.request("round-" + round + "-" + index, url, new byte[0], index % 2 == 1));
                }

                assertTrue("Native pool did not overlap", ready.await(10, TimeUnit.SECONDS));
                release.countDown();
                for (int index = 0; index < captures.size(); index++) {
                    Capture capture = captures.get(index);

                    if (index % 2 == 1) capture.code(88);
                    else {
                        capture.await();
                        assertEquals(PluginResult.Status.OK.ordinal(), capture.result.getStatus());
                        JSONObject metadata = new JSONObject(capture.result.getMultipartMessage(0).getMessage());

                        assertEquals(200, metadata.getInt("status"));
                        assertEquals(urls.get(index), metadata.getString("url"));
                        assertEquals(2, capture.result.getMultipartMessagesSize());
                    }
                }

                harness.drained(5);
                for (Capture capture : captures) assertEquals(1, capture.count.get());
            }

            assertTrue("Work was serial", peak.get() >= 2);
            System.out.println("UltraHTTP parallel TLS: 24 requests, peak native tasks=" + peak.get());
        } finally { release.countDown(); }
    }

    @Test
    public void capacityDuplicateResetAndOwnedBufferCleanup() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int index = 0; index < 2; index++) pool.execute(() -> {
            blocked.countDown();
            try { release.await(20, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });

        try (Harness harness = new Harness(pool)) {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            List<Capture> captures = new ArrayList<>();
            byte[] callerBody = new byte[128];

            Arrays.fill(callerBody, (byte) 7);
            for (int index = 0; index < 32; index++) {
                captures.add(harness.request("queued-" + index, "https://example.org/", callerBody, false));
            }

            List<byte[]> owned = harness.ownedBodies();

            assertEquals(32, owned.size());
            harness.request("overflow", "https://example.org/", callerBody, false).code(91);
            harness.request("queued-0", "https://example.org/", callerBody, false).code(91);
            harness.plugin.onReset();
            harness.plugin.onReset();
            for (Capture capture : captures) capture.code(90);
            release.countDown();
            harness.drained(5);
            for (byte[] buffer : owned) assertArrayEquals(new byte[buffer.length], buffer);
            for (byte value : callerBody) assertEquals(7, value);
            for (Capture capture : captures) assertEquals(1, capture.count.get());
            // Admission must recover after cleanup, including reuse of an old id.
            harness.request("queued-0", "http://invalid.test/", callerBody, false).code(1);
            harness.drained(5);
        } finally { release.countDown(); }
    }

    @Test
    public void cancellingSaturatedTLSHandshakesReleasesResourcesPromptly() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        CountDownLatch connected = new CountDownLatch(4);
        ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        ExecutorService listener = Executors.newSingleThreadExecutor();
        Harness harness = new Harness(Executors.newFixedThreadPool(4));

        listener.execute(() -> {
            try {
                for (int index = 0; index < 4; index++) {
                    accepted.add(server.accept());
                    connected.countDown();
                }
            } catch (java.io.IOException closed) {
                // Expected when the fixture closes during failed-test cleanup.
                if (!server.isClosed()) System.out.println("UltraHTTP fixture accept failed: " + closed.getClass().getSimpleName());
            }
        });
        try {
            List<Capture> captures = new ArrayList<>();
            String url = "https://127.0.0.1:" + server.getLocalPort() + "/blocked-handshake";

            for (int index = 0; index < 8; index++) {
                captures.add(harness.request("cancel-" + index, url, new byte[]{4, 5, 6}, false));
            }

            assertTrue("Native TLS did not connect", connected.await(10, TimeUnit.SECONDS));
            List<byte[]> owned = harness.ownedBodies();
            ExecutorService cancellations = Executors.newFixedThreadPool(4);

            try {
                List<Future<?>> cancelled = new ArrayList<>();

                for (int index = 0; index < 8; index++) {
                    String id = "cancel-" + index;

                    cancelled.add(cancellations.submit(() -> { harness.cancel(id); return null; }));
                }
                cancelled.add(cancellations.submit(() -> { harness.plugin.onReset(); return null; }));
                for (Future<?> result : cancelled) result.get(5, TimeUnit.SECONDS);
            } finally { cancellations.shutdownNow(); }

            for (Capture capture : captures) capture.code(90);
            harness.drained(5);
            for (byte[] buffer : owned) assertArrayEquals(new byte[buffer.length], buffer);
            for (Capture capture : captures) assertEquals(1, capture.count.get());
            // Registry cleanup alone is insufficient: peers must observe EOF too.
            for (Socket socket : accepted) {
                socket.setSoTimeout(2000);
                byte[] handshake = new byte[4096];

                while (socket.getInputStream().read(handshake) != -1) { }
            }
        } finally {
            server.close();
            for (Socket socket : accepted) socket.close();
            listener.shutdownNow();
            harness.close();
        }
    }

    @Test
    public void repeatedCancelAndResetRacesDoNotLeakThreadsSocketsOrJobs() throws Exception {
        for (int round = 0; round < 8; round++) {
            cancellingSaturatedTLSHandshakesReleasesResourcesPromptly();
        }
    }

    @Test
    public void resetRacingExecutorRejectionCompletesCallbackOnce() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable task) {
                admitted.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new RejectedExecutionException("Fixture rejected after reset");
            }
        };
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (Harness harness = new Harness(pool)) {
            Future<Capture> pending = caller.submit(() -> harness.request("rejection-race", "https://example.org/", new byte[]{7}, false));

            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            List<byte[]> owned = harness.ownedBodies();

            assertEquals(1, owned.size());
            harness.plugin.onReset();
            release.countDown();
            pending.get(5, TimeUnit.SECONDS).code(90);
            harness.drained(1);
            for (byte[] body : owned) assertArrayEquals(new byte[body.length], body);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    public void destroyStopsAdmissionAndDoesNotCancelTwice() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        pool.execute(() -> {
            blocked.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try (Harness harness = new Harness(pool)) {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            Capture queued = harness.request("destroy-queued", "https://example.org/", new byte[]{7}, false);
            List<byte[]> owned = harness.ownedBodies();

            harness.plugin.onDestroy();
            harness.plugin.onDestroy();
            queued.code(90);
            harness.request("after-destroy", "https://example.org/", new byte[]{9}, false).code(90);
            release.countDown();
            harness.drained(5);
            for (byte[] body : owned) assertArrayEquals(new byte[body.length], body);
            assertEquals(1, queued.count.get());
        } finally { release.countDown(); }
    }

    @Test
    public void rejectedExecutorDoesNotLeakAdmissionOrCallback() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();

        pool.shutdown();
        try (Harness harness = new Harness(pool)) {
            for (int index = 0; index < 100; index++) {
                harness.request("rejected", "https://example.org/", new byte[]{9}, false).code(-1);
                harness.drained(1);
            }
        }
    }
}
