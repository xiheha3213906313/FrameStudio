package studio.yingxu.frame;

import android.graphics.Bitmap;
import android.net.nsd.NsdServiceInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Studio.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MacContractTest {
    private static final String SERVER = "b738e1e2-3529-4ec7-9917-73c0f56d80a0";
    private static final String DEVICE = "eb01f9d9-269b-4e48-886f-808b147b0ec0";
    private static final String MODEL = "3476cb95-1a83-4cc3-a87e-e03ea756bf65";
    private static final String LORA = "a7d0cd0b-e1ac-4269-87f4-c8c9a39d590c";
    private static final String TOKEN = "a".repeat(43), CODE = "012345";
    private static KeyStore certificate;
    private static String pin;
    private Studio app;
    private byte[] png;

    @BeforeClass public static void certificate() throws Exception {
        File key = File.createTempFile("frames-test-cert", ".p12");
        assertTrue(key.delete());
        char[] pass = "test-only".toCharArray();
        File tool = new File(System.getProperty("java.home"), "bin/keytool");
        if (!tool.exists()) tool = new File(tool.getPath() + ".exe");
        try {
            Process create = new ProcessBuilder(tool.getPath(), "-genkeypair", "-alias", "mac", "-storetype", "PKCS12",
                "-keystore", key.getPath(), "-storepass", new String(pass), "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=MFlux Studio Test", "-validity", "10").redirectErrorStream(true).start();
            assertEquals(new String(create.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, create.waitFor());
            certificate = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(key)) { certificate.load(in, pass); }
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(certificate.getCertificate("mac").getEncoded()))
                digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            pin = digest.toString();
        } finally { Files.deleteIfExists(key.toPath()); }
    }

    @Before public void setup() throws Exception {
        app = (Studio) RuntimeEnvironment.getApplication();
        Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff307459);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        bitmap.recycle();
        png = out.toByteArray();
    }

    @After public void closeStore() { if (app != null) app.db.close(); }

    @Test public void probesTlsWithoutHttpAndSendsSixDigitStringAfterConfirmation() throws Exception {
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try (Server server = new Server()) {
            Future<?> probe = server.expectHandshakeOnly();
            String fingerprint = MacTransport.inspectFingerprint(server.endpoint, null);
            probe.get();
            assertEquals(pin, fingerprint);
            MacPairing attempt = new MacPairing(server.endpoint, fingerprint, null);
            assertFalse(attempt.prompt().getBoolean("confirmed"));
            assertEquals(pin.substring(0, 16).toUpperCase(Locale.ROOT), attempt.prompt().getString("fingerprint"));
            assertThrows(IOException.class, () -> attempt.send(null, CODE, "Android Test"));
            assertTrue(attempt.confirm().getBoolean("confirmed"));
            for (String invalid : Arrays.asList("", "01234", "1234567", "１２３４５６", " 12345", "ABCDEF"))
                assertThrows(IOException.class, () -> attempt.send(null, invalid, "Android Test"));

            Future<Request> request = server.reply(201, paired());
            MacConnection.Session session = attempt.send(null, CODE, "Android Test");
            assertPairCode(request.get(), CODE);
            assertEquals(SERVER, session.serverId);
            assertEquals(DEVICE, session.deviceId);
            assertEquals(pin, session.pin);
            assertTrue(attempt.finished());
            assertThrows(IOException.class, () -> attempt.send(null, CODE, "Android Test"));
            Future<Request> models = server.reply(200, "{}");
            session.client().json("/v1/models", null, 200);
            assertEquals("Bearer " + TOKEN, models.get().headers.get("authorization"));
            assertThrows(IOException.class, () -> session.client().output(DEVICE,
                "https://192.168.1.1/v1/jobs/" + DEVICE + "/output", (mime, length, input) -> null));

            MacPairing reused = new MacPairing(server.endpoint, pin, SERVER);
            Future<Request> replay = server.reply(403, pairingError("pairing_invalid"));
            MacTransport.Failure consumed = assertThrows(MacTransport.Failure.class, () -> reused.send(null, CODE, "Android Test"));
            assertEquals("pairing_invalid", consumed.code);
            assertPairCode(replay.get(), CODE);
        }
        assertSame(factory, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier());
    }

    @Test public void rejectsCorrectCodeAfterFiveWrongAttemptsAndAcceptsFreshCode() throws Exception {
        try (Server server = new Server()) {
            MacPairing attempt = new MacPairing(server.endpoint, pin, null);
            attempt.confirm();
            for (int i = 1; i <= 5; i++) {
                int status = i == 5 ? 429 : 403;
                String code = i == 5 ? "pairing_locked" : "pairing_invalid";
                Future<Request> request = server.reply(status, pairingError(code));
                MacTransport.Failure failure = assertThrows(MacTransport.Failure.class,
                    () -> attempt.send(null, "987654", "Android Test"));
                assertEquals(status, failure.status);
                assertEquals(code, failure.code);
                assertEquals(i == 5, attempt.finished());
                assertPairCode(request.get(), "987654");
            }
            assertThrows(IOException.class, () -> attempt.send(null, CODE, "Android Test"));
            MacPairing stillLocked = new MacPairing(server.endpoint, pin, SERVER);
            Future<Request> rejected = server.reply(429, pairingError("pairing_locked"));
            assertTrue(MacPairing.locked(assertThrows(MacTransport.Failure.class,
                () -> stillLocked.send(null, CODE, "Android Test"))));
            assertPairCode(rejected.get(), CODE);

            MacPairing fresh = new MacPairing(server.endpoint, pin, null);
            fresh.confirm();
            Future<Request> request = server.reply(201, paired());
            assertEquals(DEVICE, fresh.send(null, "045678", "Android Test").deviceId);
            assertPairCode(request.get(), "045678");
        }
    }

    @Test public void expiredCodeKeepsVerifiedFingerprintForCorrection() throws Exception {
        try (Server server = new Server()) {
            MacPairing attempt = new MacPairing(server.endpoint, pin, SERVER);
            Future<Request> expired = server.reply(403, pairingError("pairing_invalid"));
            MacTransport.Failure failure = assertThrows(MacTransport.Failure.class,
                () -> attempt.send(null, CODE, "Android Test"));
            assertEquals("pairing_invalid", failure.code);
            assertTrue(failure.getMessage().contains("已过期"));
            assertPairCode(expired.get(), CODE);
            assertFalse(attempt.finished());

            Future<Request> fresh = server.reply(201, paired());
            assertEquals(DEVICE, attempt.send(null, "000789", "Android Test").deviceId);
            assertPairCode(fresh.get(), "000789");
        }
    }

    @Test public void enforcesFullPinBeforeSendingAnyCodeOrCredentials() throws Exception {
        try (Server server = new Server()) {
            Future<Request> rejected = server.rejectHandshake();
            MacPairing mismatch = new MacPairing(server.endpoint, pin.substring(0, 16) + "0".repeat(48), null);
            assertEquals(pin.substring(0, 16).toUpperCase(Locale.ROOT), mismatch.confirm().getString("fingerprint"));
            assertThrows(SSLHandshakeException.class, () -> mismatch.send(null, CODE, "Android Test"));
            assertNull(rejected.get(8, TimeUnit.SECONDS));

            rejected = server.rejectHandshake();
            MacTransport wrongDevice = new MacTransport(server.endpoint, "0".repeat(64), TOKEN);
            assertThrows(SSLHandshakeException.class, () -> wrongDevice.json("/v1/models", null, 200));
            assertNull(rejected.get(8, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> new MacTransport(server.endpoint, pin, "").json("/v1/models", null, 200));
        }
    }

    private static void assertPairCode(Request request, String code) throws Exception {
        assertEquals("POST /v1/pair HTTP/1.1", request.start);
        assertEquals("application/json", request.headers.get("content-type"));
        assertFalse(request.headers.containsKey("authorization"));
        JSONObject payload = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
        assertEquals(2, payload.length());
        assertEquals(code, payload.get("code"));
        assertEquals("Android Test", payload.getString("deviceName"));
    }

    private static String paired() throws Exception {
        return new JSONObject().put("version", 1).put("serverId", SERVER).put("deviceId", DEVICE).put("token", TOKEN).toString();
    }

    private static String pairingError(String code) throws Exception {
        return new JSONObject().put("error", new JSONObject().put("code", code).put("message", code)).toString();
    }

    @Test public void reconnectsExistingDeviceAfterRestartAndEndpointChange() throws Exception {
        try (Server original = new Server(); Server relocated = new Server()) {
            MacConnection.Session saved = original.session();
            original.dropResponse();
            IOException offline = assertThrows(IOException.class, () -> MacConnection.reconnect(saved, Collections.emptyList()));
            assertTrue(offline.getMessage().startsWith("等待连接 Mac"));
            assertFalse(offline.getMessage().contains("重新配对"));

            Future<Request> sameAddress = original.reply(200, catalog().put("version", 1).toString());
            assertEquals(original.endpoint, MacConnection.reconnect(saved, Collections.emptyList()).endpoint);
            assertEquals("Bearer " + TOKEN, sameAddress.get().headers.get("authorization"));

            MacDiscovery.Service advertisement = new MacDiscovery.Service("Mac", SERVER, relocated.endpoint);
            original.dropResponse();
            relocated.reply(200, catalog().put("version", 1).put("serverId", DEVICE).toString());
            assertThrows(IOException.class, () -> MacConnection.reconnect(saved, Collections.singletonList(advertisement)));

            original.dropResponse();
            Future<Request> newAddress = relocated.reply(200, catalog().put("version", 1).toString());
            MacConnection.Verified reconnect = MacConnection.reconnect(saved, Collections.singletonList(advertisement));
            assertEquals(relocated.endpoint, reconnect.endpoint);
            assertEquals("Bearer " + TOKEN, newAddress.get().headers.get("authorization"));
            MacConnection.Session moved = saved.at(reconnect.endpoint);
            assertEquals(DEVICE, moved.deviceId);
            assertEquals(SERVER, moved.serverId);
            assertEquals(pin, moved.pin);

            String id = MacJobs.enqueue(app, body("txt2img"), saved, catalog());
            app.db.macUpdate(id, MacJobs.WAITING, new JSONObject().put("attempted", true).put("accepted", true), Long.MAX_VALUE);
            assertTrue(app.db.resumeMac(moved.serverId, moved.deviceId, true));
            Future<Request> poll = relocated.reply(200, job(id, "txt2img", "queued").toString());
            new MacJobs(app, () -> moved).run(app.db.nextMac(System.currentTimeMillis()));
            assertEquals("GET /v1/jobs/" + id + " HTTP/1.1", poll.get().start);
            assertEquals(id, app.db.find(id).getString("id"));

            String downloadId = MacJobs.enqueue(app, body("txt2img"), saved, catalog());
            String outputPath = "/v1/jobs/" + downloadId + "/output";
            JSONObject output = new JSONObject().put("path", outputPath).put("mimeType", "image/png")
                .put("width", 64).put("height", 32);
            app.db.update(downloadId, "download_error", new JSONObject().put("attempted", true)
                .put("downloadFailure", "incomplete_body")
                .put("remote", job(downloadId, "txt2img", "succeeded").put("output", output)));
            assertFalse(app.db.resumeMac(moved.serverId, moved.deviceId, false));
            assertTrue(app.db.resumeMac(moved.serverId, moved.deviceId, true));
            Future<Request> download = relocated.reply(200, "image/png", png, png.length);
            new MacJobs(app, () -> moved).run(app.db.nextMac(System.currentTimeMillis()));
            assertEquals("GET " + outputPath + " HTTP/1.1", download.get().start);
            assertEquals("done", app.db.find(downloadId).getString("state"));

            Future<Request> revoked = relocated.reply(401, "{\"error\":{\"code\":\"unauthorized\"}}");
            MacTransport.Failure invalid = assertThrows(MacTransport.Failure.class,
                () -> MacConnection.reconnect(moved, Collections.emptyList()));
            assertTrue(MacConnection.credentialsInvalid(invalid));
            assertTrue(invalid.getMessage().contains("重新配对"));
            revoked.get();
        }
    }

    @Test public void resolvesAdvertisedIPv4AndIgnoresUnsupportedBonjourVersion() throws Exception {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Studio");
        info.setPort(42817);
        info.setAttribute("v", "1");
        info.setAttribute("IPv4", "192.168.2.3");
        info.setAttribute("SERVERID", SERVER.toUpperCase(Locale.ROOT));
        info.setHostAddresses(Collections.singletonList(InetAddress.getByName("192.168.2.8")));
        MacDiscovery.Service service = MacDiscovery.Service.from(info);
        assertEquals("Studio", service.name);
        assertEquals(SERVER, service.serverId);
        assertEquals("https://192.168.2.3:42817", service.endpoint.toString());
        info.setAttribute("IPv4", "untrusted.example");
        assertEquals("https://192.168.2.8:42817", MacDiscovery.Service.from(info).endpoint.toString());
        info.setAttribute("v", "2");
        assertNull(MacDiscovery.Service.from(info));
    }

    @Test public void submitsFourOperationsUsingActualFieldsAndOrderedImageBindings() throws Exception {
        try (Server server = new Server()) {
            MacConnection.Session session = server.session();
            String ref1 = reference(), ref2 = reference();
            for (String op : Arrays.asList("txt2img", "img2img", "inpaint", "upscale")) {
                JSONObject body = body(op);
                JSONObject options = body.getJSONObject("macOptions").put("seed", 4294967295L);
                if (!op.equals("txt2img")) body.getJSONArray("refs").put(ref1);
                if (op.equals("img2img") || op.equals("inpaint")) {
                    body.getJSONArray("refs").put(ref2);
                    body.put("prompt", "改 @image 2，保留 mail@example.com");
                    body.put("promptRefs", new JSONArray().put(new JSONObject().put("start", 2).put("end", 10).put("ref", ref2)));
                    options.put("loras", new JSONArray().put(new JSONObject().put("id", LORA).put("strength", 0.9)));
                    if (op.equals("img2img")) options.put("imageStrength", 0.58);
                    else body.put("macBase", ref2).put("strokes", new JSONArray("[{\"diameter\":0.08,\"erases\":false,\"points\":[{\"x\":0.2,\"y\":0.4}]}]"));
                }
                if (op.equals("upscale")) body.put("macBase", ref1).put("prompt", "").getJSONObject("macOptions")
                    .put("shortEdge", 64).put("softness", 0.21);
                String id = MacJobs.enqueue(app, body, session, catalog());
                Future<Request> sent = server.reply(202, job(id, op, "queued").toString());
                new MacJobs(app, () -> session).run(app.db.find(id));
                Request request = sent.get(8, TimeUnit.SECONDS);
                JSONObject wire = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                assertEquals("POST /v1/jobs HTTP/1.1", request.start);
                assertEquals(Integer.toString(request.body.length), request.headers.get("content-length"));
                assertFalse(request.headers.containsKey("transfer-encoding"));
                assertEquals(id, wire.getString("id"));
                assertEquals(op, wire.getString("operation"));
                assertEquals(MODEL, wire.getString("modelId"));
                assertEquals(4294967295L, wire.getJSONObject("options").getLong("seed"));
                JSONArray images = wire.getJSONArray("inputs");
                assertEquals(op.equals("txt2img") ? 0 : op.equals("img2img") ? 2 : 1, images.length());
                for (int i = 0; i < images.length(); i++) assertArrayEquals(png, Base64.getDecoder().decode(images.getString(i)));
                if (op.equals("inpaint")) {
                    assertEquals("改 @image 1，保留 mail@example.com", wire.getString("prompt"));
                    assertEquals(1, wire.getJSONArray("strokes").length());
                    assertEquals(ref2, app.db.find(id).getJSONObject("body").getJSONArray("macInputs").getString(0));
                }
                if (op.equals("img2img")) {
                    assertEquals("改 @image 2，保留 mail@example.com", wire.getString("prompt"));
                    assertEquals(0.58, wire.getJSONObject("options").getDouble("imageStrength"), 0.001);
                    assertEquals(LORA, wire.getJSONObject("options").getJSONArray("loras").getJSONObject(0).getString("id"));
                }
                if (op.equals("upscale")) {
                    assertFalse(wire.has("prompt"));
                    assertFalse(wire.has("strokes"));
                    assertFalse(wire.getJSONObject("options").has("scale"));
                    assertEquals(64, wire.getJSONObject("options").getInt("shortEdge"));
                }
                assertEquals(MacJobs.REMOTE, app.db.find(id).getString("state"));
            }
        }
    }

    @Test public void reconcilesInterruptedPostAndReusesExactBytesAfterDatabaseReopens() throws Exception {
        try (Server server = new Server()) {
            MacConnection.Session session = server.session();
            String id = MacJobs.enqueue(app, body("txt2img").put("count", 2), session, catalog());
            assertEquals(2, app.db.all().length());
            assertNotEquals(app.db.all().getJSONObject(0).getString("id"), app.db.all().getJSONObject(1).getString("id"));
            Future<Request> first = server.dropResponse();
            new MacJobs(app, () -> session).run(app.db.find(id));
            assertEquals(MacJobs.WAITING, app.db.find(id).getString("state"));
            app.db.close();
            app.db = new Store(app);
            Future<Request> lookup = server.reply(404, "{\"error\":{\"code\":\"not_found\"}}");
            Future<Request> second = server.reply(202, job(id, "txt2img", "queued").toString());
            new MacJobs(app, () -> session).run(app.db.find(id));
            assertEquals("GET /v1/jobs/" + id + " HTTP/1.1", lookup.get().start);
            assertArrayEquals(first.get().body, second.get().body);
            Future<Request> poll = server.reply(200, job(id, "txt2img", "running").toString());
            new MacJobs(app, () -> session).run(app.db.find(id));
            assertEquals("GET /v1/jobs/" + id + " HTTP/1.1", poll.get().start);
        }
    }

    @Test public void cancelsAmbiguousUploadAfterCheckingThatMacDidNotCreateTheJob() throws Exception {
        try (Server server = new Server()) {
            MacConnection.Session session = server.session();
            MacJobs jobs = new MacJobs(app, () -> session);
            String id = MacJobs.enqueue(app, body("txt2img"), session, catalog());
            Future<Request> upload = server.dropResponse();
            jobs.run(app.db.find(id));
            assertEquals("POST /v1/jobs HTTP/1.1", upload.get().start);
            assertTrue(app.db.requestMacCancel(id));
            for (String expected : Arrays.asList(MacJobs.REMOTE, "cancelled")) {
                Future<Request> lookup = server.reply(404, "{\"error\":{\"code\":\"not_found\"}}");
                jobs.run(app.db.find(id));
                assertEquals("GET /v1/jobs/" + id + " HTTP/1.1", lookup.get().start);
                assertEquals(expected, app.db.find(id).getString("state"));
            }
        }
    }

    @Test public void cancellationWaitsForMacTerminalStateAndDownloadRetriesWithoutGenerating() throws Exception {
        try (Server server = new Server()) {
            MacConnection.Session session = server.session();
            MacJobs jobs = new MacJobs(app, () -> session);
            String local = MacJobs.enqueue(app, body("txt2img"), session, catalog());
            assertTrue(app.db.requestMacCancel(local));
            jobs.run(app.db.find(local));
            assertEquals("cancelled", app.db.find(local).getString("state"));

            String id = MacJobs.enqueue(app, body("txt2img"), session, catalog());
            JSONObject running = job(id, "txt2img", "running");
            server.reply(202, running.toString());
            jobs.run(app.db.find(id));
            assertTrue(app.db.requestMacCancel(id));
            server.reply(200, running.toString());
            Future<Request> cancel = server.reply(202, running.put("cancelRequested", true).toString());
            jobs.run(app.db.find(id));
            assertEquals("POST /v1/jobs/" + id + "/cancel HTTP/1.1", cancel.get().start);
            assertEquals("0", cancel.get().headers.get("content-length"));
            assertEquals(0, cancel.get().body.length);
            assertEquals(MacJobs.REMOTE, app.db.find(id).getString("state"));

            String path = "/v1/jobs/" + id.toUpperCase(Locale.ROOT) + "/output";
            JSONObject succeeded = job(id, "txt2img", "succeeded").put("output",
                new JSONObject().put("path", path).put("mimeType", "image/png").put("width", 64).put("height", 32));
            server.reply(200, succeeded.toString());
            for (String expected : Arrays.asList(MacJobs.WAITING, MacJobs.WAITING, "download_error")) {
                Future<Request> truncated = server.reply(200, "image/png", Arrays.copyOf(png, png.length - 12), png.length);
                jobs.run(app.db.find(id));
                assertEquals("GET " + path.toLowerCase(Locale.ROOT) + " HTTP/1.1", truncated.get().start);
                JSONObject row = app.db.find(id);
                assertEquals(expected, row.getString("state"));
                assertTrue(row.getJSONObject("result").getString("message").contains("已收到 " + (png.length - 12) + " / " + png.length));
                assertFalse(new File(app.images, id + "-0.part").exists());
            }
            assertFalse(new File(app.images, id + "-0").exists());
            assertTrue(app.db.recoverMac(id));
            Future<Request> download = server.reply(200, "image/png", png, png.length);
            jobs.run(app.db.find(id));
            assertEquals("GET " + path.toLowerCase(Locale.ROOT) + " HTTP/1.1", download.get().start);
            assertEquals("done", app.db.find(id).getString("state"));
            assertArrayEquals(png, Files.readAllBytes(new File(app.images, id + "-0").toPath()));
            assertTrue(new File(app.images, id + "-0.thumb").isFile());
            assertEquals(4294967295L, app.db.find(id).getJSONObject("result").getJSONObject("remote")
                .getJSONObject("options").getLong("seed"));
        }
    }

    @Test public void reconnectResumesLegacyMacDownloadsAndKeepsArkRecordsUntouched() throws Exception {
        try (Server server = new Server()) {
            MacConnection.Session session = server.session();
            String id = MacJobs.enqueue(app, body("txt2img"), session, catalog());
            String path = "/v1/jobs/" + id + "/output";
            JSONObject succeeded = job(id, "txt2img", "succeeded").put("output",
                new JSONObject().put("path", path).put("mimeType", "image/png").put("width", 64).put("height", 32));
            app.db.update(id, "download_error", new JSONObject().put("remote", succeeded).put("attempted", true)
                .put("message", "与 Mac 的连接已中断，响应不完整。"));
            app.db.add("ark-job", new JSONObject());
            app.db.update("ark-job", "download_error", new JSONObject());
            assertFalse(app.db.resumeMac(SERVER, "other-device", true));
            assertEquals("download_error", app.db.find(id).getString("state"));
            assertTrue(app.db.resumeMac(SERVER, DEVICE, true));
            assertEquals(MacJobs.FETCHING, app.db.find(id).getString("state"));
            assertEquals("download_error", app.db.find("ark-job").getString("state"));

            MacJobs jobs = new MacJobs(app, () -> session);
            server.reply(200, "image/png", Arrays.copyOf(png, 20), png.length);
            jobs.run(app.db.find(id));
            assertEquals(MacJobs.WAITING, app.db.find(id).getString("state"));
            assertTrue(app.db.resumeMac(SERVER, DEVICE, true));
            Future<Request> recovered = server.reply(200, "image/png", png, png.length);
            jobs.run(app.db.find(id));
            assertEquals("GET " + path + " HTTP/1.1", recovered.get().start);
            assertEquals("done", app.db.find(id).getString("state"));
            assertArrayEquals(png, Files.readAllBytes(new File(app.images, id + "-0").toPath()));
        }
    }

    @Test public void preservesOrientationOfOriginalSnapshotsAndPreviews() throws Exception {
        File original = new File(app.refs, Studio.id() + ".jpg"), thumb = new File(app.refs, Studio.id() + ".jpg");
        Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(original)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
        } finally { bitmap.recycle(); }
        android.media.ExifInterface exif = new android.media.ExifInterface(original.getPath());
        exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6");
        exif.saveAttributes();
        assertArrayEquals(new int[]{32, 64}, MacImages.dimensions(original, null));
        MacImages.preview(original, thumb);
        assertArrayEquals(new int[]{32, 64}, MacImages.dimensions(thumb, null));
    }

    @Test public void arkSerializesOnlyManagedLabels() throws Exception {
        JSONObject body = body("img2img").put("prompt", "参考 @图二 和 @图一，邮件 a@b.com，普通 @图一")
            .put("refs", new JSONArray().put("first").put("second"))
            .put("promptRefs", new JSONArray().put(new JSONObject().put("start", 3).put("end", 6).put("ref", "second"))
                .put(new JSONObject().put("start", 9).put("end", 12).put("ref", "first")));
        assertEquals("参考 图2 和 图1，邮件 a@b.com，普通 @图一", PromptReferences.serialize(body, body.getJSONArray("refs"), false));
        assertThrows(IOException.class, () -> PromptReferences.serialize(body, new JSONArray().put("first"), false));
    }

    private JSONObject body(String op) throws Exception {
        return new JSONObject().put("source", "mac").put("operation", op).put("serverId", SERVER).put("modelId", MODEL)
            .put("prompt", "一座山").put("count", 1).put("refs", new JSONArray()).put("macOptions", new JSONObject());
    }

    private String reference() throws Exception {
        String name = Studio.id() + ".source";
        Files.write(new File(app.refs, name).toPath(), png);
        return name;
    }

    private static JSONObject job(String id, String operation, String state) throws Exception {
        return new JSONObject().put("id", id.toUpperCase(Locale.ROOT)).put("modelId", MODEL.toUpperCase(Locale.ROOT))
            .put("operation", operation).put("state", state).put("options", new JSONObject().put("seed", 4294967295L));
    }

    private static JSONObject catalog() throws Exception {
        JSONArray operations = new JSONArray();
        for (String op : Arrays.asList("txt2img", "img2img", "inpaint", "upscale")) {
            operations.put(new JSONObject().put("id", op).put("minInputs", op.equals("txt2img") ? 0 : 1)
                .put("maxInputs", op.equals("txt2img") ? 0 : op.equals("img2img") ? 4 : 1)
                .put("size", new JSONObject().put("step", 16).put("maxEdge", 4096))
                .put("scales", new JSONArray().put("2x")).put("defaultScale", "2x").put("maxOutputEdge", 8192)
                .put("softness", new JSONObject().put("min", 0).put("max", 1))
                .put("imageStrength", new JSONObject().put("min", 0.01).put("max", 1))
                .put("seed", new JSONObject().put("min", 0).put("max", 4294967295L))
                .put("loras", new JSONArray().put(new JSONObject().put("id", LORA).put("name", "Test LoRA"))));
        }
        return new JSONObject().put("serverId", SERVER).put("limits", new JSONObject())
            .put("models", new JSONArray().put(new JSONObject().put("id", MODEL).put("name", "Fixture model")
                .put("ready", true).put("operations", operations)));
    }

    private static final class Request {
        final String start;
        final Map<String, String> headers = new HashMap<>();
        final byte[] body;
        Request(DataInputStream input) throws IOException {
            start = input.readLine();
            if (start == null) throw new EOFException();
            for (String line; !(line = input.readLine()).isEmpty();) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
            input.readFully(body);
        }
    }

    private static final class Server implements AutoCloseable {
        final SSLServerSocket socket;
        final URI endpoint;
        final ExecutorService loop = Executors.newSingleThreadExecutor();
        Server() throws Exception {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(certificate, "test-only".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(8000);
            endpoint = URI.create("https://localhost:" + socket.getLocalPort());
        }
        MacConnection.Session session() { return new MacConnection.Session(endpoint, pin, SERVER, DEVICE, TOKEN); }
        Future<Request> rejectHandshake() {
            return loop.submit(() -> {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(8000);
                    IOException failure = assertThrows(IOException.class, () -> client.getInputStream().read());
                    assertFalse(failure instanceof SocketTimeoutException);
                    return null;
                }
            });
        }
        Future<?> expectHandshakeOnly() {
            return loop.submit(() -> {
                try (SSLSocket client = (SSLSocket) socket.accept()) {
                    client.setSoTimeout(8000);
                    client.startHandshake();
                    assertEquals(-1, client.getInputStream().read());
                    return null;
                }
            });
        }
        Future<Request> dropResponse() { return reply(0, "", new byte[0], 0); }
        Future<Request> reply(int status, String json) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return reply(status, "application/json", bytes, bytes.length);
        }
        Future<Request> reply(int status, String type, byte[] bytes, int length) {
            return loop.submit(() -> {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(8000);
                    Request request = new Request(new DataInputStream(new BufferedInputStream(client.getInputStream())));
                    if (status != 0) {
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: " + type + "\r\nContent-Length: " +
                            length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(bytes);
                        out.flush();
                    }
                    return request;
                }
            });
        }
        @Override public void close() throws IOException { socket.close(); loop.shutdownNow(); }
    }
}
