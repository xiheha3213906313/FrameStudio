package studio.yingxu.frame;

import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Base64OutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MacJobs {
    static final long BODY_LIMIT = 64L * 1024 * 1024;
    static final String QUEUED = "mac_queued", REMOTE = "mac_remote", WAITING = "mac_waiting",
        FETCHING = "mac_fetching", BLOCKED = "mac_blocked";
    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList("txt2img", "img2img", "inpaint", "upscale"));

    static boolean isMac(JSONObject body) { return "mac".equals(body.optString("source")); }

    static JSONObject model(JSONObject catalog, String id) throws Exception {
        JSONArray models = catalog.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (id.equalsIgnoreCase(model.getString("id"))) return model;
        }
        throw new IOException("Mac 模型不在当前目录，请刷新「我的 Mac」模型。");
    }

    static JSONObject capability(JSONObject model, String operation) throws Exception {
        if (!model.optBoolean("ready")) throw new IOException("模型未就绪，请在 Mac 检查权重并刷新目录。");
        JSONArray operations = model.getJSONArray("operations");
        for (int i = 0; i < operations.length(); i++) {
            JSONObject item = operations.getJSONObject(i);
            if (operation.equals(item.optString("id"))) return item;
        }
        throw new IOException("该 Mac 模型不支持此操作，请选择其他模型。");
    }

    static String enqueue(Studio app, JSONObject body) throws Exception {
        MacConnection.Session session = app.mac.session();
        JSONObject catalog = app.mac.catalog();
        return enqueue(app, body, session, catalog);
    }

    static String enqueue(Studio app, JSONObject body, MacConnection.Session session, JSONObject catalog) throws Exception {
        if (session == null || catalog == null || !session.serverId.equals(body.optString("serverId")) ||
            !session.serverId.equalsIgnoreCase(catalog.optString("serverId")))
            throw new IOException("请连接原 Mac 并刷新模型目录。");
        String operation = body.getString("operation");
        if (!OPERATIONS.contains(operation)) throw new IOException("未知的 Mac 操作。");
        String modelId = MacTransport.uuid(body.getString("modelId"));
        JSONObject model = model(catalog, modelId), capability = capability(model, operation);
        JSONArray refs = body.getJSONArray("refs");
        if (refs.length() > 14) throw new IOException("最多可保留 14 张参考图。");
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < refs.length(); i++) {
            String ref = refs.getString(i);
            if (!distinct.add(ref)) throw new IOException("参考图重复。");
            input(app, ref);
        }
        JSONArray effective = refs, files = refs;
        if ("inpaint".equals(operation) || "upscale".equals(operation)) {
            String base = body.getString("macBase");
            if (!distinct.contains(base)) throw new IOException("请重新选择底图。");
            String original = original(app, base);
            effective = new JSONArray().put(base);
            files = new JSONArray().put(original);
        }
        if (effective.length() < capability.getInt("minInputs") || effective.length() > capability.getInt("maxInputs"))
            throw new IOException("参考图数量不符合当前 Mac 模型要求。");
        JSONObject limits = catalog.getJSONObject("limits");
        int[] dimensions = null;
        for (int i = 0; i < files.length(); i++) {
            int[] current = MacImages.dimensions(input(app, files.getString(i)), limits);
            if (i == 0) dimensions = current;
        }
        JSONObject options = options(body.optJSONObject("macOptions"), capability, operation, dimensions);
        JSONObject request = new JSONObject().put("operation", operation).put("modelId", modelId).put("options", options);
        if (!"upscale".equals(operation)) {
            String prompt = PromptReferences.serialize(body, effective, true).trim();
            if (prompt.isEmpty() || prompt.indexOf(0) >= 0 || prompt.getBytes(StandardCharsets.UTF_8).length > 16384)
                throw new IOException("请输入有效的提示词，UTF-8 长度最多 16 KiB。");
            request.put("prompt", prompt);
        }
        if ("inpaint".equals(operation)) request.put("strokes", strokes(body.getJSONArray("strokes")));
        int count = body.getInt("count");
        if (count < 1 || count > 15 || "upscale".equals(operation) && count != 1) throw new IOException("生成张数超出范围。");
        body.put("source", "mac").put("deviceId", session.deviceId).put("modelId", modelId).put("name", model.getString("name"))
            .put("model", "mac:" + session.serverId + ":" + modelId).put("macInputs", files).put("macOptions", options).put("count", 1);
        body.remove("rate");
        body.remove("strokes");  // Stored with the immutable JSON to keep job rows small.
        JSONObject loras = new JSONObject();
        JSONArray library = capability.optJSONArray("loras"), choices = options.optJSONArray("loras");
        for (int i = 0; choices != null && i < choices.length(); i++) {
            String chosen = choices.getJSONObject(i).getString("id");
            for (int j = 0; library != null && j < library.length(); j++) {
                JSONObject item = library.getJSONObject(j);
                if (chosen.equals(item.getString("id"))) loras.put(chosen, item.getString("name"));
            }
        }
        body.put("macLoraNames", loras).put("macFixedCfg", capability.opt("fixedGuidance"))
            .put("macSamplerDefault", capability.opt("samplerDefaultLabel"));
        String first = Studio.id();
        String header = request.toString();
        Payload payload = new Payload(app, files, first, header);
        if (payload.length > Math.min(BODY_LIMIT, limits.optLong("maxBodyBytes", BODY_LIMIT)))
            throw new IOException("Base64 编码后的请求超过 64 MiB，请减少参考图或改用较小的图片。");
        String name = first + ".mac-request";
        body.put("macRequest", name);
        File snapshot = new File(app.refs, name);
        File part = new File(app.refs, name + ".part");
        boolean stored = false;
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!part.renameTo(snapshot)) throw new IOException("无法保存 Mac 请求快照。");
            android.database.sqlite.SQLiteDatabase db = app.db.getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < count; i++) app.db.add(i == 0 ? first : Studio.id(), body, QUEUED);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                app.db.revision.incrementAndGet();
            }
            stored = true;
        } finally {
            part.delete();
            if (!stored) snapshot.delete();
        }
        return first;
    }

    static String readSnapshot(Studio app, JSONObject body) throws Exception {
        try (InputStream in = new FileInputStream(input(app, body.getString("macRequest")))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Ark.copy(in, out, 4L * 1024 * 1024);
            return out.toString("UTF-8");
        }
    }

    static String original(Studio app, String ref) throws IOException {
        input(app, ref);
        String original = ref + ".original";
        if (new File(app.refs, original).isFile()) return original;
        if (ref.endsWith(".source")) return ref;
        throw new IOException("原图快照不可用，请从系统相册重新选择原图。");
    }

    static File input(Studio app, String name) throws IOException {
        if (!Studio.safe(name)) throw new IOException("参考图名称无效。");
        File file = new File(app.refs, name);
        if (!file.isFile()) throw new IOException("参考图已失效，请重新添加。");
        return file;
    }

    private static JSONObject options(JSONObject selected, JSONObject cap, String operation, int[] input) throws Exception {
        JSONObject out = new JSONObject();
        if (selected == null) selected = new JSONObject();
        numeral(selected, out, "seed", cap.optJSONObject("seed"), true);
        if (cap.optBoolean("lowRAM") && selected.has("lowRAM")) out.put("lowRAM", selected.getBoolean("lowRAM"));
        if ("upscale".equals(operation)) {
            JSONArray scales = cap.getJSONArray("scales");
            String scale = selected.optString("scale");
            double factor;
            if (selected.has("shortEdge")) {
                if (!scale.isEmpty()) throw new IOException("放大倍率和目标短边只能选一项。");
                int edge = selected.getInt("shortEdge");
                if (edge < 1) throw new IOException("请输入有效的目标短边。");
                out.put("shortEdge", edge);
                factor = (double) edge / Math.min(input[0], input[1]);
            } else {
                String effective = scale.isEmpty() ? cap.getString("defaultScale") : scale;
                if (!has(scales, effective)) throw new IOException("此模型不支持该放大倍率。");
                factor = Double.parseDouble(effective.substring(0, effective.length() - 1));
                if (!scale.isEmpty()) out.put("scale", scale);
            }
            if (Math.max(input[0], input[1]) * factor > cap.getInt("maxOutputEdge"))
                throw new IOException("放大后超过 Mac 最长边上限，请降低倍率或目标短边。");
            numeral(selected, out, "softness", cap.optJSONObject("softness"), false);
            return out;
        }

        if (selected.has("width") || selected.has("height")) {
            int w = selected.getInt("width"), h = selected.getInt("height");
            JSONObject size = cap.getJSONObject("size");
            int step = size.getInt("step"), edge = size.getInt("maxEdge");
            if (w < step || h < step || w % step != 0 || h % step != 0 || Math.max(w,h) > edge ||
                "inpaint".equals(operation) && !repairSizes(input[0], input[1], step).contains(w + "x" + h))
                throw new IOException("画布尺寸不符合模型步长、底图比例或像素上限。");
            out.put("width", w).put("height", h);
        }
        numeral(selected, out, "steps", cap.optJSONObject("steps"), true);
        numeral(selected, out, "guidance", cap.optJSONObject("guidance"), false);
        numeral(selected, out, "imageStrength", cap.optJSONObject("imageStrength"), false);
        String sampler = selected.optString("sampler");
        if (!sampler.isEmpty()) {
            if (!ids(cap.optJSONArray("samplers")).contains(sampler)) throw new IOException("采样器不可用，请刷新模型目录。");
            out.put("sampler", sampler);
        }
        String negative = selected.optString("negativePrompt");
        if (cap.optBoolean("negativePrompt") && !negative.isEmpty()) {
            if (negative.indexOf(0) >= 0 || negative.getBytes(StandardCharsets.UTF_8).length > 16384)
                throw new IOException("负面提示词含无效字符或超过 16 KiB。");
            out.put("negativePrompt", negative);
        }
        JSONArray chosen = selected.optJSONArray("loras");
        if (chosen != null && chosen.length() > 0) {
            Set<String> library = ids(cap.optJSONArray("loras")), used = new HashSet<>();
            JSONArray loras = new JSONArray();
            if (chosen.length() > 16) throw new IOException("最多选择 16 个 LoRA。");
            for (int i = 0; i < chosen.length(); i++) {
                JSONObject item = chosen.getJSONObject(i);
                String id = item.getString("id");
                double strength = item.getDouble("strength");
                if (!library.contains(id) || !used.add(id) || !Double.isFinite(strength) || strength < 0 || strength > 2)
                    throw new IOException("LoRA 已不可用，或强度超出 0–2。请重新选择。");
                loras.put(new JSONObject().put("id", id).put("strength", strength));
            }
            out.put("loras", loras);
        }
        return out;
    }

    private static void numeral(JSONObject options, JSONObject output, String key, JSONObject rule, boolean integer) throws Exception {
        if (rule == null || !options.has(key) || options.isNull(key)) return;
        double value = options.getDouble(key);
        if (!Double.isFinite(value) || value < rule.optDouble("min", 0) || value > rule.optDouble("max", Double.MAX_VALUE) ||
            integer && value != Math.rint(value)) throw new IOException("Mac 参数超出范围，请检查 " + key + "。");
        if (integer) output.put(key, (long) value);
        else output.put(key, value);
    }

    static Set<String> repairSizes(int w, int h, int step) {
        int a = w, b = h;
        while (b != 0) { int next = a % b; a = b; b = next; }
        int exactW = w / a * step, exactH = h / a * step, exactEdge = Math.max(exactW, exactH), longEdge = Math.max(w, h);
        Set<String> sizes = new HashSet<>();
        for (int edge : new int[]{512, 768, 1024, 1280, 1536, 2048, Math.min(2048, longEdge)}) {
            int width, height;
            if (exactEdge <= 1024) {
                int factor = Math.max(1, Math.min(2048 / exactEdge, (int) Math.round((double) edge / exactEdge)));
                width = exactW * factor;
                height = exactH * factor;
            } else {
                double scale = (double) edge / longEdge / step;
                width = Math.max(1, (int) Math.round(w * scale)) * step;
                height = Math.max(1, (int) Math.round(h * scale)) * step;
            }
            sizes.add(width + "x" + height);
        }
        if (longEdge <= 2048 && w % step == 0 && h % step == 0) sizes.add(w + "x" + h);
        return sizes;
    }

    private static JSONArray strokes(JSONArray strokes) throws Exception {
        int points = 0;
        boolean paint = false;
        if (strokes.length() > 512) throw new IOException("局部编辑最多支持 512 条笔迹。");
        for (int i = 0; i < strokes.length(); i++) {
            JSONObject stroke = strokes.getJSONObject(i);
            JSONArray path = stroke.getJSONArray("points");
            double diameter = stroke.getDouble("diameter");
            paint |= !stroke.getBoolean("erases");
            points += path.length();
            if (!(diameter >= 0.001 && diameter <= 1) || path.length() == 0 || points > 50000)
                throw new IOException("遮罩笔迹超过范围，请清理部分笔迹。");
            for (int j = 0; j < path.length(); j++) {
                JSONObject point = path.getJSONObject(j);
                double x = point.getDouble("x"), y = point.getDouble("y");
                if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) throw new IOException("遮罩坐标超出底图。");
            }
        }
        if (!paint) throw new IOException("请先涂出要编辑的区域。");
        return strokes;
    }

    private static boolean has(JSONArray list, String value) {
        for (int i = 0; i < list.length(); i++) if (value.equals(list.optString(i))) return true;
        return false;
    }

    private static Set<String> ids(JSONArray list) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; list != null && i < list.length(); i++) ids.add(list.optJSONObject(i).optString("id"));
        return ids;
    }

    static final class Payload {
        final long length;
        private final File[] files;
        private final byte[] prefix;
        Payload(Studio app, JSONArray inputs, String id, String header) throws Exception {
            if (!header.startsWith("{") || !header.endsWith("}")) throw new IOException("任务请求快照已损坏。");
            prefix = ("{\"id\":" + JSONObject.quote(MacTransport.uuid(id)) + "," +
                header.substring(1, header.length() - 1) + ",\"inputs\":[").getBytes(StandardCharsets.UTF_8);
            long bytes = prefix.length + 2;
            files = new File[inputs.length()];
            for (int i = 0; i < files.length; i++) {
                files[i] = input(app, inputs.getString(i));
                bytes += (i == 0 ? 2 : 3) + 4 * ((files[i].length() + 2) / 3);
            }
            length = bytes;
        }

        void write(OutputStream out) throws IOException {
            out.write(prefix);
            for (int i = 0; i < files.length; i++) {
                if (i > 0) out.write(',');
                out.write('"');
                try (InputStream in = new FileInputStream(files[i]);
                    Base64OutputStream encoder = new Base64OutputStream(out, Base64.NO_WRAP | Base64.NO_CLOSE)) {
                    Ark.copy(in, encoder, MacImages.INPUT_BYTES);
                }
                out.write('"');
            }
            out.write(']');
            out.write('}');
        }
    }

    private final Studio app;
    private final java.util.concurrent.Callable<MacConnection.Session> connection;
    MacJobs(Studio app) { this(app, app.mac::session); }
    MacJobs(Studio app, java.util.concurrent.Callable<MacConnection.Session> connection) {
        this.app = app;
        this.connection = connection;
    }

    void run(JSONObject task) {
        String id = task.optString("id"), path = "/v1/jobs/" + id;
        JSONObject body = task.optJSONObject("body"), result = task.optJSONObject("result");
        try {
            if (!result.optBoolean("attempted") && app.db.macCancelRequested(id)) {
                app.db.update(id, "cancelled", result);
                return;
            }
            JSONObject saved = result.optJSONObject("remote");
            if (saved != null && "succeeded".equals(saved.optString("state"))) {
                if (FETCHING.equals(task.optString("state"))) {
                    result.remove("downloadAttempts");
                    result.put("attempts", 0);
                }
                download(null, body, id, result);
                return;
            }
            MacConnection.Session session;
            try { session = pairedSession(body); }
            catch (Exception error) { blocked(id, result, MacTransport.explain(error)); return; }
            MacTransport client = session.client();
            JSONObject remote = null;
            if (result.optBoolean("attempted")) {
                try { remote = client.json(path, null, 200); }
                catch (MacTransport.Failure failure) {
                    if (failure.status != 404 || !"not_found".equals(failure.code)) throw failure;
                    if (result.optBoolean("accepted")) {
                        failed(id, result, "Mac 找不到原任务。请检查 Mac 的任务存储和原配对设备。");
                        return;
                    }
                    if (app.db.macCancelRequested(id)) {
                        if (result.optBoolean("queueRejected") || result.optBoolean("cancelProbe"))
                            app.db.update(id, "cancelled", result);
                        else {
                            // Give an earlier upload time to settle before concluding it created no remote job.
                            result.put("cancelProbe", true);
                            result.remove("message");
                            app.db.macUpdate(id, REMOTE, result, System.currentTimeMillis() + 2000);
                        }
                        return;
                    }
                }
            }
            if (remote == null) {
                if (!result.optBoolean("attempted") && app.db.macCancelRequested(id)) {
                    app.db.update(id, "cancelled", result);
                    return;
                }
                Payload payload;
                try { payload = new Payload(app, body.getJSONArray("macInputs"), id, readSnapshot(app, body)); }
                catch (Exception error) {
                    failed(id, result, "输入或请求快照不可用。请重新选择图片并生成。");
                    return;
                }
                if (payload.length > BODY_LIMIT) {
                    failed(id, result, "请求快照超出 Mac 接口限制。");
                    return;
                }
                result.put("attempted", true);
                result.remove("queueRejected");
                app.db.macUpdate(id, REMOTE, result, 0);
                remote = client.json("/v1/jobs", payload.length, payload::write, 200, 202);
            }
            validate(remote, id, body);
            result.put("accepted", true).put("remote", remote).put("attempts", 0);
            result.remove("message");
            String state = remote.getString("state");
            if (("queued".equals(state) || "running".equals(state)) &&
                app.db.macCancelRequested(id) && !result.optBoolean("cancelAcknowledged")) {
                app.db.macUpdate(id, REMOTE, result, 0);
                remote = client.json(path + "/cancel", MacTransport.EMPTY, 200, 202);
                validate(remote, id, body);
                result.put("remote", remote).put("cancelAcknowledged", remote.optBoolean("cancelRequested"));
                state = remote.getString("state");
            }
            switch (state) {
                case "queued":
                case "running":
                    app.db.macUpdate(id, REMOTE, result, System.currentTimeMillis() + ("queued".equals(state) ? 3500 : 2200));
                    break;
                case "cancelled": app.db.update(id, "cancelled", result); break;
                case "failed":
                    JSONObject error = remote.optJSONObject("error");
                    failed(id, result, error == null ? "Mac 推理失败，请查看 Mac 运行日志。" :
                        "interrupted".equals(error.optString("code")) ? "Mac 推理已中断。检查 Mac 后可复用参数重新生成。" : error.optString("message"));
                    break;
                case "succeeded":
                    app.db.macUpdate(id, FETCHING, result, 0);
                    download(client, body, id, result);
                    break;
                default: failed(id, result, "Mac 返回了未知的任务状态。");
            }
        } catch (MacTransport.Failure error) {
            if (error.status == 401) blocked(id, result, error.getMessage());
            else if (error.status == 429 || error.status >= 500) {
                if ("queue_full".equals(error.code) && !result.optBoolean("accepted")) Studio.with(result, "queueRejected", true);
                waitForMac(id, result, error.getMessage());
            }
            else failed(id, result, error.getMessage());
        } catch (Exception error) {
            if (MacTransport.certificateFailure(error)) blocked(id, result, MacTransport.explain(error));
            else if (error instanceof IOException || error instanceof org.json.JSONException)
                waitForMac(id, result, MacTransport.explain(error));
            else failed(id, result, MacTransport.explain(error));
        }
    }

    private static void validate(JSONObject remote, String id, JSONObject body) throws Exception {
        if (!id.equals(MacTransport.uuid(remote.getString("id"))) ||
            !body.getString("modelId").equals(MacTransport.uuid(remote.getString("modelId"))) ||
            !body.getString("operation").equals(remote.getString("operation")))
            throw new IOException("Mac 返回的任务身份与本机记录不一致。");
    }

    private MacConnection.Session pairedSession(JSONObject body) throws Exception {
        MacConnection.Session session = connection.call();
        if (session == null || !session.owns(body))
            throw new IOException("此任务属于原配对设备。请恢复原 Mac 连接和凭证。");
        return session;
    }

    private void download(MacTransport client, JSONObject body, String id, JSONObject result) {
        File finalFile = new File(app.images, id + "-0"), part = new File(app.images, id + "-0.part");
        File thumb = new File(app.images, id + "-0.thumb"), thumbPart = new File(app.images, id + "-0.thumb.part");
        boolean downloading = false;
        try {
            JSONObject metadata = result.getJSONObject("remote").getJSONObject("output");
            int width = metadata.getInt("width"), height = metadata.getInt("height");
            if (!"image/png".equals(metadata.optString("mimeType"))) throw new IOException("Mac 任务未返回 PNG 原图。");
            if (!finalFile.isFile()) {
                downloading = true;
                if (client == null) client = pairedSession(body).client();
                client.output(id, metadata.getString("path"), (type, length, in) -> {
                    if (!"image/png".equals(type) || length <= 0 || length > MacImages.OUTPUT_BYTES)
                        throw new IOException("Mac 返回的原图格式或文件大小无效。");
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        Ark.copy(in, out, MacImages.OUTPUT_BYTES);
                        out.getFD().sync();
                    }
                    return null;
                });
                downloading = false;
                MacImages.verifyPng(part, width, height);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(part.getPath(), bounds);
                if (bounds.outWidth != width || bounds.outHeight != height) throw new IOException("Mac 原图无法解码。");
                if (!part.renameTo(finalFile)) throw new IOException("无法归档原图，请检查手机存储空间。");
            }
            if (!thumb.isFile()) {
                PictureProvider.thumbnail(finalFile, thumbPart);
                if (!thumbPart.renameTo(thumb)) throw new IOException("无法归档缩略图，请检查手机存储空间。");
            }
            result.remove("message");
            result.remove("downloadFailure");
            result.remove("downloadAttempts");
            result.put("images", new JSONArray().put(new JSONObject().put("file", finalFile.getName())
                .put("ready", true).put("size", width + "x" + height)));
            app.db.update(id, "done", result);
        } catch (Exception error) {
            downloadFailed(id, result, error, downloading);
        } finally { part.delete(); thumbPart.delete(); }
    }

    private void downloadFailed(String id, JSONObject result, Exception error, boolean downloading) {
        MacTransport.Failure response = error instanceof MacTransport.Failure ? (MacTransport.Failure) error : null;
        String message = MacTransport.explain(error);
        if (response != null && "output_unavailable".equals(response.code)) {
            message = "Mac 上的原图已过期或被删除。请在 Mac 检查作品，或复用参数重新生成。";
        } else if (response != null) {
            message += "（Mac HTTP " + response.status + (response.code.isEmpty() ? "" : " · " + response.code) + "）";
        }
        boolean disconnected = error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException;
        boolean interrupted = error instanceof EOFException || error instanceof java.net.SocketException ||
            error instanceof java.net.SocketTimeoutException ||
            (error instanceof javax.net.ssl.SSLException && !MacTransport.certificateFailure(error)) ||
            (response != null && (response.status == 429 || response.status >= 500));
        String failure = error instanceof MacTransport.IncompleteBody ? "incomplete_body"
            : downloading && interrupted ? "connection" : "other";
        Studio.with(result, "downloadFailure", failure);
        if (downloading && disconnected) {
            waitForMac(id, result, message);
            return;
        }
        if (downloading && interrupted && result.optInt("downloadAttempts") < 2) {
            Studio.with(result, "downloadAttempts", result.optInt("downloadAttempts") + 1);
            waitForMac(id, result, message);
            return;
        }
        if (error instanceof MacTransport.IncompleteBody)
            message += "请在 Mac 检查该任务的原图和局域网发送日志。";
        app.db.update(id, "download_error", Studio.with(result, "message", message));
    }

    private void failed(String id, JSONObject result, String message) {
        app.db.update(id, "failed", Studio.with(result, "message", message));
    }
    private void blocked(String id, JSONObject result, String message) {
        app.db.update(id, BLOCKED, Studio.with(result, "message", message));
    }
    private void waitForMac(String id, JSONObject result, String message) {
        int attempts = Math.min(8, result.optInt("attempts") + 1);
        Studio.with(result, "attempts", attempts);
        Studio.with(result, "message", message);
        app.db.macUpdate(id, WAITING, result, System.currentTimeMillis() + Math.min(60000, 1000L << attempts));
    }
}
