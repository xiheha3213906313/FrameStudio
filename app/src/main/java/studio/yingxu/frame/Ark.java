package studio.yingxu.frame;

import android.util.Base64;
import android.util.Base64OutputStream;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class Ark {
    static class NotSent extends IOException {
        NotSent(Exception e) { super(ArkError.explain(e)); }
    }
    static class Rejected extends IOException {
        final int status;
        Rejected(int status, String message) { super(message); this.status = status; }
    }
    static HttpsURLConnection connection(String url) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000); c.setReadTimeout(300000); c.setUseCaches(false);
        return c;
    }
    static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] bytes = new byte[32768]; int n; long count = 0;
        while ((n = in.read(bytes)) != -1) {
            count += n; if (count > limit) throw new IOException("文件过大");
            out.write(bytes,0,n);
        }
    }
    static JSONObject response(HttpsURLConnection c) throws Exception {
        int status = c.getResponseCode();
        boolean ok = status >= 200 && status < 300;
        String trace = c.getHeaderField("X-Request-ID");
        if (trace == null) trace = c.getHeaderField("X-Tt-Logid");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(InputStream in = ok ? c.getInputStream() : c.getErrorStream()) {
            if (in != null) copy(in, bytes, ok ? 16L*1024*1024 : 256*1024);
        }
        JSONObject j = Studio.json(bytes.toString("UTF-8"));
        JSONObject err=j.optJSONObject("error");
        if (!ok || err!=null) {
            // An explicit error inside a successful HTTP envelope is still a rejected generation.
            throw new Rejected(ok?400:status, ArkError.describe(status,err,trace,c.getHeaderField("Retry-After")));
        }
        if (trace != null) j.put("trace",trace);
        return j;
    }
    static JSONArray models(String key) throws Exception {
        HttpsURLConnection c = connection(Studio.BASE + "/models");
        try {
            c.setReadTimeout(30000); c.setRequestProperty("Authorization","Bearer "+key);
            JSONArray raw = response(c).getJSONArray("data"), list = new JSONArray();
            for (int i=0; i<raw.length(); i++) {
                JSONObject m = raw.getJSONObject(i);
                String id = m.optString("id");
                JSONObject modalities = m.optJSONObject("modalities");
                JSONArray outputs = modalities == null ? null : modalities.optJSONArray("output_modalities");
                boolean match = "ImageGeneration".equalsIgnoreCase(m.optString("domain")) || id.startsWith("doubao-seedream-");
                for (int k=0; outputs!=null && k<outputs.length(); k++) if ("image".equalsIgnoreCase(outputs.optString(k))) match=true;
                JSONArray kinds=m.optJSONArray("task_type");
                for(int k=0;kinds!=null && k<kinds.length();k++) if("ImageGeneration".equalsIgnoreCase(kinds.optString(k)))match=true;
                if (match && !id.isEmpty()) list.put(new JSONObject().put("id",id).put("status",m.optString("status")).put("name",m.optString("name")));
            }
            return list;
        } finally { c.disconnect(); }
    }
    static JSONObject generate(Studio app, JSONObject body, String key) throws Exception {
        JSONArray refs = body.getJSONArray("refs");
        int count = body.getInt("count");
        String prompt = body.getString("prompt");
        JSONObject payload = new JSONObject().put("model",body.getString("model"))
            .put("prompt",count>1 ? prompt+"\n请生成一组 "+count+" 张关联图片。" : prompt)
            .put("response_format","url").put("watermark",body.optBoolean("watermark",true));
        if (body.optBoolean("layers")) {
            payload.put("layer_decomposition",true).put("size",body.getString("level"));
            if (body.getJSONObject("rate").optString("family").equals("55"))
                payload.put("layer_decomposition_options",new JSONObject().put("max_images",body.getInt("layerMax")));
        } else {
            if (!body.optString("size").isEmpty()) payload.put("size",body.getString("size"));
            if (!body.optString("format","auto").equals("auto"))payload.put("output_format",body.getString("format"));
            if (body.optBoolean("transparent"))payload.put("background","transparent").put("output_format","png");
        }
        if (count > 1) payload.put("sequential_image_generation","auto")
            .put("sequential_image_generation_options",new JSONObject().put("max_images",count));
        // Stream references into the JSON request. Full-resolution files never accumulate in the heap.
        String base = payload.toString();
        String head = refs.length()==0 ? base : base.substring(0,base.length()-1)+",\"image\":[";
        long length = head.getBytes(StandardCharsets.UTF_8).length;
        String[] prefixes = new String[refs.length()]; File[] files = new File[refs.length()];
        for (int i=0;i<refs.length();i++) {
            String name = refs.getString(i);
            if (!Studio.safe(name)) throw new IOException("参考图名称无效");
            files[i] = new File(app.refs,name);
            if (!files[i].isFile()) throw new IOException("参考图已移除，请重新选择");
            prefixes[i] = (i==0 ? "" : ",")+"\"data:" + PictureProvider.mime(files[i]) + ";base64,";
            length += prefixes[i].length()+4*((files[i].length()+2)/3)+1;
        }
        if (refs.length()>0) length+=2;
        HttpsURLConnection c = connection(Studio.BASE+"/images/generations");
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(length);
            c.setRequestProperty("Authorization","Bearer "+key); c.setRequestProperty("Content-Type","application/json");
            OutputStream stream;
            try { stream=c.getOutputStream(); } catch(IOException e) { throw new NotSent(e); }
            try(OutputStream out = new BufferedOutputStream(stream)) {
                out.write(head.getBytes(StandardCharsets.UTF_8));
                for (int i=0;i<files.length;i++) {
                    out.write(prefixes[i].getBytes(StandardCharsets.US_ASCII));
                    try(InputStream input = new FileInputStream(files[i]); Base64OutputStream enc = new Base64OutputStream(out,Base64.NO_WRAP|Base64.NO_CLOSE)) {
                        copy(input,enc,30L*1024*1024);
                    }
                    out.write('"');
                }
                if (refs.length()>0) { out.write(']'); out.write('}'); }
            }
            return response(c);
        } finally { c.disconnect(); }
    }
    static JSONObject generationResult(String id, JSONObject response) throws JSONException {
        JSONArray data=response.getJSONArray("data"), images=new JSONArray(), errors=new JSONArray();
        for(int i=0;i<data.length();i++) {
            JSONObject item=data.getJSONObject(i);
            if(item.optJSONObject("error")!=null) {
                errors.put("第 "+(i+1)+" 张："+ArkError.describe(0,item.getJSONObject("error"),null,null));
                continue;
            }
            if(item.optString("url").isEmpty()) {
                errors.put("第 "+(i+1)+" 张缺少下载地址，请核对方舟用量。");
                continue;
            }
            images.put(new JSONObject().put("url",item.getString("url")).put("size",item.optString("size"))
                .put("file",id+"-"+i).put("name",item.optString("name")).put("ready",false));
        }
        return new JSONObject().put("images",images).put("errors",errors).put("usage",response.optJSONObject("usage"))
            .put("trace",response.optString("trace")).put("responseTime",System.currentTimeMillis())
            .put("responseModel",response.optString("model"));
    }

    static void download(String link, File file) throws Exception {
        for (int hops=0;hops<5;hops++) {
            URL url=new URL(link);
            if (!"https".equals(url.getProtocol())) throw new IOException("图片链接协议无效");
            HttpsURLConnection c=connection(link); c.setReadTimeout(60000);
            try {
                int status=c.getResponseCode();
                if (status>=300 && status<400 && c.getHeaderField("Location")!=null) {
                    link=new URL(url,c.getHeaderField("Location")).toString(); continue;
                }
                if (status!=200) throw new IOException(status==403 || status==410?"下载地址已过期或无权访问（HTTP "+status+"），请核对生成时间。":"原图下载失败（HTTP "+status+"）");
                try(InputStream in=c.getInputStream(); OutputStream out=new FileOutputStream(file)) { copy(in,out,120L*1024*1024); }
                return;
            } finally { c.disconnect(); }
        }
        throw new IOException("图片链接重定向过多");
    }
}
