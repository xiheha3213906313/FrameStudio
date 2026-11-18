package studio.yingxu.frame;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String HOST="frame.local", HOME="https://"+HOST+"/index.html";
    private WebView view;
    private Studio app;
    private final ExecutorService io=Executors.newSingleThreadExecutor(), network=Executors.newSingleThreadExecutor();
    private String picker="";
    private boolean ready, showTasks;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app=(Studio)getApplication();
        showTasks=getIntent().getBooleanExtra("tasks",false);
        if(state!=null) picker=state.getString("picker","");
        view=new WebView(this);view.setBackgroundColor(0xfff8f7f3);
        WebSettings settings=view.getSettings();settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false); settings.setBlockNetworkLoads(true);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest req){
                Uri url=req.getUrl(); String p=url.getPath();
                if(HOST.equals(url.getHost()) && "https".equals(url.getScheme()) && p!=null && p.startsWith("/") && !p.contains("..")) {
                    try {
                        if(p.startsWith("/images/") || p.startsWith("/refs/")){
                            String name=url.getLastPathSegment();
                            if(!Studio.safe(name)) throw new FileNotFoundException();
                            File f=new File(p.startsWith("/refs/")?app.refs:app.images,name);
                            return new WebResourceResponse(name.endsWith(".thumb")?"image/jpeg":PictureProvider.mime(f),null,new FileInputStream(f));
                        }
                        String mime=p.endsWith(".html")?"text/html":p.endsWith(".css")?"text/css":p.endsWith(".js")?"application/javascript":p.endsWith(".jpg")?"image/jpeg":"application/octet-stream";
                        return new WebResourceResponse(mime,null,getAssets().open(p.substring(1)));
                    } catch(IOException ignored){}
                }
                return new WebResourceResponse("text/plain","UTF-8",404,"Missing",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView w,String url){ready=true;if(showTasks){js("window.navigate('tasks')");showTasks=false;}}
        });
        view.addJavascriptInterface(new Bridge(),"StudioNative");setContentView(view);view.loadUrl(HOME);
        if(app.db.next()!=null) app.wake();
        io.execute(()->pruneRefs());
    }
    private void pruneRefs() {
        Set<String> used=new HashSet<>();
        JSONArray tasks=app.db.all();
        for(int i=0;i<tasks.length();i++) collect(used,tasks.optJSONObject(i).optJSONObject("body").optJSONArray("refs"));
        collect(used,Studio.json(app.prefs.getString("draft","{}")).optJSONArray("refs"));
        File[] files=app.refs.listFiles();
        if(files!=null) for(File f:files) if(!used.contains(f.getName().replace(".thumb","")) && System.currentTimeMillis()-f.lastModified()>86400000L) f.delete();
    }
    private void collect(Set<String>s,JSONArray arr){if(arr!=null)for(int i=0;i<arr.length();i++)s.add(arr.optString(i));}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("picker",picker);}
    @Override public void onNewIntent(Intent i){super.onNewIntent(i);if(i.getBooleanExtra("tasks",false))js("window.navigate('tasks')");}
    @Override public void onBackPressed(){
        view.evaluateJavascript("window.studioBack ? window.studioBack() : false",v->{if(!"true".equals(v))super.onBackPressed();});
    }
    @Override public void onDestroy(){ready=false;io.shutdown();network.shutdown();view.removeJavascriptInterface("StudioNative");view.destroy();super.onDestroy();}
    private void js(String script){runOnUiThread(()->{if(!isDestroyed() && !isFinishing()) view.evaluateJavascript(script,null);});}
    private void reply(String id,boolean ok,Object value){
        js("window.nativeReply("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(String.valueOf(value))+")");
    }
    private void notifyUI(String text){js("window.toast("+JSONObject.quote(text)+")");}
    private class Bridge {
        @JavascriptInterface public void call(String id,String action,String raw) {
            if(isDestroyed())return;
            ExecutorService pool="sync".equals(action)?network:io;
            pool.execute(()->{
                try { Object answer=handle(action,Studio.json(raw));reply(id,true,answer); }
                catch(Exception e){reply(id,false,ArkError.explain(e));}
            });
        }
    }
    private Object handle(String action,JSONObject d) throws Exception {
        switch(action) {
            case "init": return new JSONObject().put("hasKey",app.prefs.contains("secret"))
                .put("models",new JSONArray(app.prefs.getString("models","[]"))).put("synced",app.prefs.getLong("synced",0))
                .put("custom",new JSONArray(app.prefs.getString("custom","[]"))).put("draft",Studio.json(app.prefs.getString("draft","{}")));
            case "draft": app.prefs.edit().putString("draft",d.toString()).apply();return true;
            case "key":
                if(app.db.busy()) throw new IOException("请等待当前任务结束后再更换密钥");
                String key=d.optString("key").trim();
                if(key.length()>2048 || key.matches("(?s).*\\s.*"))throw new IOException("密钥中含有空白或长度异常");
                app.setKey(key);return true;
            case "sync": {
                String k=app.key();if(k.isEmpty()) throw new IOException("请先填写 API Key");
                JSONArray models=Ark.models(k);long now=System.currentTimeMillis();
                synchronized(app) {
                    if(!k.equals(app.key())) throw new IOException("密钥已更换，请重新同步");
                    app.prefs.edit().putString("models",models.toString()).putLong("synced",now).apply();
                }
                return new JSONObject().put("models",models).put("synced",now);
            }
            case "custom": app.prefs.edit().putString("custom",d.getJSONArray("list").toString()).commit();return true;
            case "jobs": {
                long rev=app.db.revision.get();
                if(d.optLong("since",-1)==rev)return JSONObject.NULL;
                JSONArray rows=app.db.all();
                for(int i=0;i<rows.length();i++) {
                    JSONArray imgs=rows.getJSONObject(i).getJSONObject("result").optJSONArray("images");
                    for(int k=0;imgs!=null && k<imgs.length();k++) imgs.getJSONObject(k).remove("url");
                }
                return new JSONObject().put("revision",rev).put("rows",rows);
            }
            case "submit": {
                if(app.key().isEmpty()) throw new IOException("请先连接方舟 API Key");
                String id=d.getString("id"); JSONObject b=d.getJSONObject("body");
                if(!Studio.safe(id) || (b.optString("prompt").trim().isEmpty() && !b.optBoolean("layers")) || b.optString("model").trim().isEmpty())throw new IOException("请填写模型与提示词");
                int count=b.getInt("count"), refs=b.getJSONArray("refs").length();
                if(count<1||count>15||refs>14||refs+count>15)throw new IOException("图片数量超出范围");
                for(int i=0;i<refs;i++){
                    String ref=b.getJSONArray("refs").getString(i);
                    if(!Studio.safe(ref)||!new File(app.refs,ref).exists())throw new IOException("参考图已失效，请重新添加");
                }
                if(b.optBoolean("layers") || b.optBoolean("transparent") && !"55".equals(b.getJSONObject("rate").getString("family"))) {
                    if(refs!=1)throw new IOException("此模式需要一张参考图");
                    File input=new File(app.refs,b.getJSONArray("refs").getString(0));
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(input.toString(),bounds);
                    if(b.optBoolean("layers") && (long)bounds.outWidth*bounds.outHeight<262144)
                        throw new IOException("拆分图层至少需要 262144 个像素，请选择更大的参考图");
                    if(b.optBoolean("transparent") && !input.getName().endsWith(".png"))
                        throw new IOException("此模型生成透明背景需要带透明通道的 PNG 参考图");
                }
                app.db.add(id,b);app.wake();return id;
            }
            case "cancel": return app.db.transition(d.getString("id"),"queued","cancelled");
            case "recover":
                if(app.db.transition(d.getString("id"),"download_error","fetching")){app.wake();return true;}
                return false;
            case "delete": {
                String id=d.getString("id");
                if(!Studio.safe(id)) throw new IOException("任务 ID 无效");
                int removed=app.db.getWritableDatabase().delete("jobs","id=? AND state NOT IN ('queued','sending','fetching')",new String[]{id});
                if(removed==0) throw new IOException("任务仍在处理中");
                app.db.revision.incrementAndGet();
                File[] files=app.images.listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith(id+"-")) f.delete();
                pruneRefs();return true;
            }
            case "pendingRefs": {
                String pending=app.prefs.getString("pendingRefs","[]");
                app.prefs.edit().remove("pendingRefs").apply();
                return new JSONArray(pending);
            }
            case "pick":
                runOnUiThread(()->{picker="refs"; Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try {startActivityForResult(i,41);} catch(ActivityNotFoundException ex){notifyUI("设备没有可用的图片选择器");}});return true;
            case "save": return export(fileFrom(d));
            case "share": {
                File f=fileFrom(d);Uri uri=new Uri.Builder().scheme("content").authority("studio.yingxu.frame.pictures").appendPath(f.getName()).build();
                Intent send=new Intent(Intent.ACTION_SEND).setType(PictureProvider.mime(f)).putExtra(Intent.EXTRA_STREAM,uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri("图片",uri));runOnUiThread(()->{try{startActivity(Intent.createChooser(send,"分享原图"));}catch(ActivityNotFoundException ex){notifyUI("设备没有可用的分享应用");}});return true;
            }
            case "open": {
                String page=d.getString("page");
                if(!Arrays.asList("apikey","docs/model-pricing","docs/image-generation-api","docs/models-api","docs/error-codes","docs/image-generation-tutorial","openManage").contains(page))throw new IOException("链接不可用");
                Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse("https://console.volcengine.com/ark/region:cn-beijing/"+page));
                runOnUiThread(()->{try{startActivity(open);}catch(ActivityNotFoundException e){notifyUI("请安装可打开网页的浏览器");}});return true;
            }
            default:throw new IOException("操作不可用");
        }
    }
    private File fileFrom(JSONObject d) throws Exception {
        String name=d.getString("file");
        if(!Studio.safe(name))throw new IOException("无效文件名");
        File f=new File(app.images,name);if(!f.isFile())throw new IOException("原图尚未保存");return f;
    }
    private boolean export(File f)throws Exception {
        ContentValues row=new ContentValues();row.put(MediaStore.Images.Media.DISPLAY_NAME,PictureProvider.exportName(f));
        row.put(MediaStore.Images.Media.MIME_TYPE,PictureProvider.mime(f));
        row.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/映序");row.put(MediaStore.Images.Media.IS_PENDING,1);
        ContentResolver resolver=getContentResolver();Uri dest=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,row);
        if(dest==null)throw new IOException("无法创建相册文件");
        try {
            try(InputStream in=new FileInputStream(f);OutputStream out=resolver.openOutputStream(dest)){Ark.copy(in,out,Long.MAX_VALUE);}
            row.clear();row.put(MediaStore.Images.Media.IS_PENDING,0);resolver.update(dest,row,null,null);return true;
        } catch(Exception e){resolver.delete(dest,null,null);throw e;}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=41 || result!=RESULT_OK || data==null)return;
        picker="";List<Uri> uris=new ArrayList<>();ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<Math.min(14,clip.getItemCount());i++)uris.add(clip.getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        io.execute(()->{
            JSONArray names=new JSONArray(); int failed=0;
            for(Uri uri:uris)try{names.put(importRef(uri));}catch(Exception e){failed++;}
            try {
                JSONArray pending=new JSONArray(app.prefs.getString("pendingRefs","[]"));
                for(int k=0;k<names.length();k++)pending.put(names.get(k));
                app.prefs.edit().putString("pendingRefs",pending.toString()).commit();
            } catch(JSONException ignored) {}
            final int failures=failed;
            js("window.receiveReferences && window.receiveReferences("+failures+")");
        });
    }
    private String importRef(Uri uri)throws Exception {
        // Normalise orientation and constrain memory, upload size and reference geometry.
        Bitmap b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,src)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            if(Math.min(w,h)<15 || (double)Math.max(w,h)/Math.min(w,h)>16)throw new IllegalArgumentException("不支持的宽高比");
            double scale=Math.min(1,2048.0/Math.max(w,h));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetSize(Math.max(15,(int)(w*scale)),Math.max(15,(int)(h*scale)));
        });
        boolean alpha=b.hasAlpha();String name=Studio.id()+(alpha?".png":".jpg");File file=new File(app.refs,name);
        try(OutputStream out=new FileOutputStream(file)){
            if(!b.compress(alpha?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,91,out))throw new IOException("无法处理参考图");
        }finally{b.recycle();}
        PictureProvider.thumbnail(file,new File(app.refs,name+".thumb"));return name;
    }
}
