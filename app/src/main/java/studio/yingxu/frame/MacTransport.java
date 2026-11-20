package studio.yingxu.frame;

import android.net.Network;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

final class MacTransport {
    static final byte[] EMPTY = new byte[0];
    private static final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "Mac request deadline");
        thread.setDaemon(true);
        return thread;
    });
    static { deadlines.setRemoveOnCancelPolicy(true); }

    static final class Failure extends IOException {
        final int status;
        final String code;
        Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    static URI endpoint(String text) throws IOException {
        try {
            URI uri = new URI(text.trim());
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null ||
                !host.matches("(0|[1-9][0-9]{0,2})([.](0|[1-9][0-9]{0,2})){3}") ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())) ||
                uri.getPort() == 0 || uri.getPort() > 65535 ||
                !(host.equals(uri.getRawAuthority()) || (host + ":" + uri.getPort()).equals(uri.getRawAuthority()))) {
                throw new IllegalArgumentException();
            }
            String[] parts = host.split("[.]");
            int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
            for (String part : parts) if (Integer.parseInt(part) > 255) throw new IllegalArgumentException();
            // Match the private IPv4 ranges offered by MFlux Studio.
            if (!(first == 10 || first == 172 && second >= 16 && second <= 31 ||
                  first == 192 && second == 168 || first == 169 && second == 254)) throw new IllegalArgumentException();
            return new URI("https", null, host, uri.getPort(), null, null, null);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("请输入 Mac 显示的 HTTPS 局域网 IPv4 地址，例如 https://192.168.1.20:42817。");
        }
    }

    static String uuid(String value) throws IOException {
        if (!value.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IOException("无效的任务或设备 UUID。");
        return UUID.fromString(value).toString();
    }

    static String fingerprint(String value) throws IOException {
        if (!value.matches("(?i)[0-9a-f]{64}")) throw new IOException("配对证书指纹格式无效。");
        return value.toLowerCase(Locale.ROOT);
    }

    static String secret(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Mac 凭证格式无效。");
        return value;
    }

    interface Writer { void write(OutputStream output) throws IOException; }
    interface Reader<T> { T read(String type, long length, InputStream input) throws Exception; }

    private interface SocketCall<T> { T run(SSLSocket socket) throws Exception; }

    static String inspectFingerprint(URI endpoint, Network network) throws Exception {
        // This isolated TLS connection closes after the handshake without writing an HTTP request.
        return new MacTransport(endpoint, network, null, "").withSocket(false, socket ->
            hex(digest((X509Certificate) socket.getSession().getPeerCertificates()[0])));
    }

    private final URI endpoint;
    private final String pin, token;
    private final SSLContext tls;
    private final Network network;

    MacTransport(URI endpoint, String pin, String token) throws Exception { this(endpoint, pin, token, null); }

    MacTransport(URI endpoint, String pin, String token, Network network) throws Exception {
        this(endpoint, network, fingerprint(pin), token);
    }

    private MacTransport(URI endpoint, Network network, String pin, String token) throws Exception {
        this.endpoint = endpoint;
        this.network = network;
        this.pin = pin;
        this.token = token.isEmpty() ? "" : secret(token);
        tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                throw new CertificateException("Client certificate not supported");
            }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing leaf");
                try {
                    chain[0].checkValidity();
                    if (pin != null && !MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),
                        hex(digest(chain[0])).getBytes(StandardCharsets.US_ASCII)))
                        throw new CertificateException("MFlux leaf fingerprint mismatch");
                } catch (GeneralSecurityException e) { throw new CertificateException(e); }
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
    }

    JSONObject json(String path, byte[] body, int... expected) throws Exception {
        return json(path, body == null ? -1 : body.length, body == null ? null : out -> out.write(body), expected);
    }

    JSONObject json(String path, long length, Writer body, int... expected) throws Exception {
        return request(path, length, body, MacTransport::readJSON, expected);
    }

    <T> T output(String id, String path, Reader<T> reader) throws Exception {
        String expected = "/v1/jobs/" + uuid(id) + "/output";
        if (!expected.equalsIgnoreCase(path)) throw new IOException("Mac 返回了无效的下载路径。");
        return request(expected, -1, null, reader, 200);
    }

    private <T> T request(String path, long length, Writer body, Reader<T> reader, int... expected) throws Exception {
        boolean requestAllowed = pin != null && (token.isEmpty() ? "/v1/pair".equals(path) && length > 0
            : path.matches("/v1/(models|jobs(/[a-f0-9-]{36}(/cancel|/output)?)?)"));
        if (!requestAllowed) throw new IOException("无效的 Mac 请求。");
        return withSocket(length > 2048 || path.endsWith("/output"),
            socket -> readResponse(socket, path, length, body, reader, expected));
    }

    private <T> T readResponse(SSLSocket socket, String path, long length, Writer body, Reader<T> reader, int[] expected)
            throws Exception {
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String headers = (body == null ? "GET " : "POST ") + path + " HTTP/1.1\r\nHost: " +
            endpoint.getRawAuthority() + "\r\nConnection: close\r\nContent-Length: " + Math.max(0, length) + "\r\n";
        if (!token.isEmpty()) headers += "Authorization: Bearer " + token + "\r\n";
        if (length > 0) headers += "Content-Type: application/json\r\n";
        out.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
        if (body != null) body.write(out);
        out.flush();
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        String[] statusLine = line(in).split(" ", 3);
        if (statusLine.length < 2 || !"HTTP/1.1".equals(statusLine[0]) || !statusLine[1].matches("[0-9]{3}"))
            throw new IOException("Mac 返回了无效的 HTTP/1.1 响应。");
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> fields = new HashMap<>();
        for (int i = 0;; i++) {
            String header = line(in);
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (i >= 32 || colon <= 0 || fields.put(header.substring(0, colon).toLowerCase(Locale.ROOT),
                header.substring(colon + 1).trim()) != null) throw new IOException("Mac 响应头无效。");
        }
        String size = fields.get("content-length");
        if (fields.containsKey("transfer-encoding") || size == null || !size.matches("[0-9]{1,10}"))
            throw new IOException("Mac 响应缺少固定长度。");
        long count = Long.parseLong(size);
        try (InputStream bounded = new FixedInput(in, count)) {
            for (int allowed : expected) if (status == allowed)
                return reader.read(fields.getOrDefault("content-type", ""), count, bounded);
            JSONObject error = null;
            try { error = readJSON(bounded).optJSONObject("error"); } catch (Exception ignored) {}
            String code = error == null ? "" : error.optString("code");
            String message = error == null ? "Mac 返回 HTTP " + status + "。" : error.optString("message");
            if (status == 401 && !token.isEmpty()) message = "Mac 已撤销此设备的凭证，请在 Mac 重新配对。";
            throw new Failure(status, code, message);
        }
    }

    private <T> T withSocket(boolean large, SocketCall<T> call) throws Exception {
        int port = endpoint.getPort() == -1 ? 443 : endpoint.getPort();
        try (Socket raw = new Socket(Proxy.NO_PROXY)) {
            if (network != null) {
                try { network.bindSocket(raw); }
                catch (IOException error) {
                    NoRouteToHostException unavailable = new NoRouteToHostException();
                    unavailable.initCause(error);
                    throw unavailable;
                }
            }
            raw.connect(new InetSocketAddress(endpoint.getHost(), port), 3500);
            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                try { raw.close(); } catch (IOException ignored) {}
            }, large ? 150 : 20, TimeUnit.SECONDS);
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, endpoint.getHost(), port, true)) {
                socket.setEnabledProtocols(Arrays.stream(socket.getSupportedProtocols())
                    .filter(p -> "TLSv1.2".equals(p) || "TLSv1.3".equals(p)).toArray(String[]::new));
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[]{"http/1.1"});
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(large ? 45000 : 6500);
                // The pinned trust manager runs before any HTTP bytes, including Authorization.
                socket.startHandshake();
                String protocol = socket.getApplicationProtocol();
                if (protocol != null && !protocol.isEmpty() && !"http/1.1".equals(protocol)) throw new IOException("Mac 未协商 HTTP/1.1。");
                return call.run(socket);
            } finally { deadline.cancel(false); }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0 && line.size() < 4096;) {
            if (c == '\r' && in.read() == '\n') return line.toString("US-ASCII");
            if (c < 32 || c > 126) break;
            line.write(c);
        }
        throw new EOFException("Mac 响应不完整。");
    }

    private static byte[] digest(X509Certificate leaf) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
    }

    static String hex(byte[] bytes) {
        String alphabet = "0123456789abcdef";
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(alphabet.charAt((value & 255) >>> 4)).append(alphabet.charAt(value & 15));
        return result.toString();
    }

    private static JSONObject readJSON(String type, long length, InputStream in) throws Exception {
        if (!type.startsWith("application/json")) throw new IOException("Mac 返回了无效的 JSON 响应。");
        return readJSON(in);
    }

    private static JSONObject readJSON(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Ark.copy(in, out, 4L * 1024 * 1024);
        return new JSONObject(out.toString("UTF-8"));
    }

    static final class IncompleteBody extends EOFException {
        IncompleteBody(long received, long length) {
            super("与 Mac 的连接已中断，响应不完整（已收到 " + received + " / " + length + " 字节）。");
        }
    }

    private static final class FixedInput extends InputStream {
        private final InputStream input;
        private final long length;
        private long remaining;
        FixedInput(InputStream input, long length) { this.input = input; this.length = length; remaining = length; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = input.read();
            received(value < 0 ? -1 : 1);
            return value;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = input.read(target, offset, (int) Math.min(length, remaining));
            received(count);
            return count;
        }
        private void received(int count) throws EOFException {
            if (count < 0) throw new IncompleteBody(length - remaining, length);
            remaining -= count;
        }
    }

    static boolean certificateFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof CertificateException) return true;
        return false;
    }

    static String explain(Exception error) {
        if (certificateFailure(error)) return "Mac 的 TLS 证书与已验证的指纹不一致或已失效，请检查 Mac 的身份及服务地址。";
        if (error instanceof SSLException) return "Mac 安全连接未完成，请检查 Mac 服务和局域网。";
        if (error instanceof ConnectException) return "Mac 未接受连接。请在 Mac 上打开 MFlux Studio 并开启「连接映序」。";
        if (error instanceof SocketTimeoutException) return "Mac 无响应，可能已休眠。请唤醒 Mac 并确认局域网服务仍在运行。";
        if (error instanceof NoRouteToHostException) return "当前设备没有到达此 Mac 的局域网路径，请检查 Wi-Fi、网络隔离和 Mac 地址。";
        return error.getMessage() == null ? "与 Mac 通信失败，请检查局域网连接。" : error.getMessage();
    }
}
