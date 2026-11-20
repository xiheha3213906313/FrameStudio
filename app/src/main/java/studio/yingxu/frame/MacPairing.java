package studio.yingxu.frame;

import android.net.Network;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class MacPairing {
    static final String LOCKED_MESSAGE = "本次配对已锁定，请在 Mac 重新生成配对码。";

    final String id = Studio.id();
    final URI endpoint;
    private final String fingerprint, expectedServerId;
    private boolean confirmed, finished;

    MacPairing(URI endpoint, String fingerprint, String expectedServerId) throws IOException {
        this.endpoint = endpoint;
        this.fingerprint = MacTransport.fingerprint(fingerprint);
        this.expectedServerId = expectedServerId;
        confirmed = expectedServerId != null;
    }

    JSONObject prompt() throws Exception {
        return new JSONObject().put("attemptId", id).put("endpoint", endpoint.toString())
            .put("fingerprint", fingerprint.substring(0, 16).toUpperCase(Locale.ROOT)).put("confirmed", confirmed);
    }

    JSONObject confirm() throws Exception {
        if (finished) throw new IOException("本次配对已结束，请重新选择 Mac。");
        confirmed = true;
        return prompt();
    }

    static boolean locked(MacTransport.Failure error) {
        return error.status == 429 && "pairing_locked".equals(error.code);
    }

    boolean finished() { return finished; }

    MacConnection.Session send(Network network, String code, String deviceName) throws Exception {
        if (finished) throw new IOException("本次配对已结束，请在 Mac 重新生成配对码。");
        if (!confirmed) throw new IOException("请先与 Mac 核对并确认证书指纹。");
        if (!code.matches("[0-9]{6}")) throw new IOException("请输入 Mac 显示的六位数字配对码。");
        byte[] body = new JSONObject().put("code", code).put("deviceName", deviceName)
            .toString().getBytes(StandardCharsets.UTF_8);
        JSONObject response;
        try { response = new MacTransport(endpoint, fingerprint, "", network).json("/v1/pair", body, 201); }
        catch (MacTransport.Failure error) {
            if (locked(error)) finished = true;
            if (error.status == 403 && "pairing_invalid".equals(error.code))
                throw new MacTransport.Failure(403, error.code, "配对码不正确、已使用或已过期。请核对配对码，或在 Mac 重新生成。");
            throw error;
        }
        finished = true;
        if (response.optInt("version") != 1) throw new IOException("此版本映序只支持 MFlux Studio 局域网 API v1。");
        String serverId = MacTransport.uuid(response.getString("serverId"));
        if (expectedServerId != null && !expectedServerId.equals(serverId))
            throw new IOException("此地址返回的 Mac 身份与已配对设备不一致。");
        return new MacConnection.Session(endpoint, fingerprint, serverId, MacTransport.uuid(response.getString("deviceId")),
            MacTransport.secret(response.getString("token")));
    }
}
