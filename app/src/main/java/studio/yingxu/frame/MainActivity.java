package studio.yingxu.frame;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String HOST="frame.local", HOME="https://"+HOST+"/index.html";
    private WebView view;
    private Studio app;
    private final ExecutorService io=Executors.newSingleThreadExecutor(), network=Executors.newSingleThreadExecutor();
    private String picker="";
    private boolean ready, showTasks;
    private boolean keyboardVisible;
    private int statusInset;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayout;
    private final Map<String,String> referenceKeys=new HashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app=(Studio)getApplication();
        Window window=getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        View decor=window.getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        showTasks=getIntent().getBooleanExtra("tasks",false);
        if(state!=null) picker=state.getString("picker","");
        view=new WebView(this);view.setBackgroundColor(0xfff8f7f3);
        WebSettings settings=view.getSettings();settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        view.setOnLongClickListener(v->true);
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
            @Override public void onPageFinished(WebView w,String url){
                ready=true;
                sendStatusInset();
                if(showTasks){js("window.navigate('tasks')");showTasks=false;}
            }
        });
        view.addJavascriptInterface(new Bridge(),"StudioNative");setContentView(view);
        observeInsets();
        view.loadUrl(HOME);
        ModelSync.schedule(app);
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
    private void observeInsets() {
        view.setOnApplyWindowInsetsListener((v,insets)->{
            int top;
            if(Build.VERSION.SDK_INT>=30) {
                top=insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()));
            } else {
                top=insets.getStableInsetTop();
            }
            if(statusInset!=top) {
                statusInset=top;
                if(ready)sendStatusInset();
            }
            return v.onApplyWindowInsets(insets);
        });
        view.requestApplyInsets();
        if(Build.VERSION.SDK_INT<30) {
            Rect visibleArea=new Rect();
            View root=view.getRootView();
            keyboardLayout=()->{
                root.getWindowVisibleDisplayFrame(visibleArea);
                int height=root.getHeight();
                int cutoff=Math.round(125*getResources().getDisplayMetrics().density);
                setKeyboardVisible(height>0 && height-visibleArea.height()>cutoff);
            };
            view.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayout);
        }
    }
    private void sendStatusInset() {
        js("window.setStatusInset && window.setStatusInset("+statusInset+")");
    }
    private void setKeyboardVisible(boolean visible) {
        if(keyboardVisible && !visible) {
            view.postDelayed(()->{
                if(!keyboardVisible && !isDestroyed())js("window.blurInputs && window.blurInputs()");
            },80);
        }
        keyboardVisible=visible;
    }
    @Override protected void onResume() {
        super.onResume();
        DisplayPolicy.preferHighRefreshRate(this,view);
    }
    @Override public void onDestroy(){
        ready=false;
        io.shutdown();
        network.shutdown();
        if(keyboardLayout!=null)view.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayout);
        view.removeJavascriptInterface("StudioNative");
        view.destroy();
        super.onDestroy();
    }
    private void js(String script){runOnUiThread(()->{if(!isDestroyed() && !isFinishing()) view.evaluateJavascript(script,null);});}
    private void reply(String id,boolean ok,Object value){
        js("window.nativeReply("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(String.valueOf(value))+")");
    }
    private void notifyUI(String text){js("window.toast("+JSONObject.quote(text)+")");}
    private class Bridge {
        @JavascriptInterface public void call(String id,String action,String raw) {
            if(isDestroyed())return;
            if("paste".equals(action)) {
                runOnUiThread(()->{
                    ClipboardManager clipboard=getSystemService(ClipboardManager.class);
                    ClipData clip=clipboard.getPrimaryClip();
                    CharSequence value=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();
                    reply(id,true,JSONObject.quote(value==null?"":value.toString()));
                });
                return;
            }
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
                app.setKey(key);ModelSync.schedule(app);return true;
            case "sync": return ModelSync.sync(app,d.optBoolean("manual"));
            case "modelCache": return ModelSync.cache(app);
            case "readPhotos": {
                JSONArray requested=d.getJSONArray("files");
                JSONArray marked=new JSONArray();
                File directory=new File(app.getFilesDir(),"viewed");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法保存已读状态");
                for(int i=0;i<requested.length();i++) {
                    String name=requested.optString(i);
                    if(!Studio.safe(name)||!new File(app.images,name).isFile())continue;
                    File marker=new File(directory,name);
                    try {
                        if(marker.isFile()||marker.createNewFile())marked.put(name);
                    } catch(IOException ignored) {}
                }
                return marked;
            }
            case "editPhoto": return importRef(ImageDecoder.createSource(fileFrom(d)));
            case "custom": app.prefs.edit().putString("custom",d.getJSONArray("list").toString()).commit();return true;
            case "jobs": {
                long rev=app.db.revision.get();
                if(d.optLong("since",-1)==rev)return JSONObject.NULL;
                JSONArray rows=app.db.all();
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);
                    if(Arrays.asList("failed","not_sent","uncertain","download_error").contains(row.optString("state")))
                        row.put("refKeys",referenceKeys(row.getJSONObject("body").optJSONArray("refs")));
                    JSONArray imgs=row.getJSONObject("result").optJSONArray("images");
                    for(int k=0;imgs!=null && k<imgs.length();k++) {
                        JSONObject image=imgs.getJSONObject(k);
                        image.remove("url");
                        if(image.optBoolean("ready"))image.put("seen",new File(new File(app.getFilesDir(),"viewed"),image.optString("file")).isFile());
                    }
                }
                return new JSONObject().put("revision",rev).put("rows",rows);
            }
            case "submit": {
                if(app.key().isEmpty()) throw new IOException("请先连接方舟 API Key");
                String id=d.getString("id"); JSONObject b=d.getJSONObject("body");
                if(!Studio.safe(id) || (b.optString("prompt").trim().isEmpty() && !b.optBoolean("layers")) || b.optString("model").trim().isEmpty())throw new IOException("请填写模型与提示词");
                int count=b.getInt("count"), refs=b.getJSONArray("refs").length();
                boolean separate=b.optBoolean("separate") && !b.optBoolean("layers");
                if(count<1||count>15||refs>14||!separate && refs+count>15)throw new IOException("图片数量超出范围");
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
                if(separate && count>1) app.db.addCopies(id,b,count);
                else app.db.add(id,b);
                app.wake();return id;
            }
            case "cancel": return app.db.transition(d.getString("id"),"queued","cancelled");
            case "recover":
                if(app.db.transition(d.getString("id"),"download_error","fetching")){app.wake();return true;}
                return false;
            case "delete": return deleteJobs(new JSONArray().put(d.getString("id")))==1;
            case "deleteMany": return deleteJobs(d.getJSONArray("ids"));
            case "restoreLink": {
                String id=d.getString("id"),link=d.getString("link").trim();
                JSONObject job=app.db.find(id);
                if(job==null || !canRestore(job))throw new IOException("当前任务无法恢复原图");
                if(!Ark.isTosLink(link))throw new IOException("请输入 TLS 日志或 TOS 中的火山引擎对象存储 HTTPS 图片链接");
                JSONObject result=job.getJSONObject("result");
                JSONArray images=result.optJSONArray("images");
                if(images==null){images=new JSONArray();result.put("images",images);}
                images.put(new JSONObject().put("url",link).put("file",id+"-restored-"+Studio.id())
                    .put("ready",false).put("imported",true));
                result.put("incomplete",true);
                app.db.update(id,"fetching",result);
                app.wake();return true;
            }
            case "restorePhoto": {
                String id=d.getString("id");
                JSONObject job=app.db.find(id);
                if(job==null || !canRestore(job))throw new IOException("当前任务无法导入原图");
                runOnUiThread(()->openPicker(42,id,false));return true;
            }
            case "pendingRefs": {
                String pending=app.prefs.getString("pendingRefs","[]");
                app.prefs.edit().remove("pendingRefs").apply();
                return new JSONArray(pending);
            }
            case "pick": {
                int limit=Math.max(1,Math.min(14,d.optInt("limit",1)));
                runOnUiThread(()->startActivityForResult(new Intent(this,AlbumActivity.class)
                    .putExtra(AlbumActivity.LIMIT,limit),41));
                return true;
            }
            case "save": return export(fileFrom(d));
            case "share": {
                File f=fileFrom(d);Uri uri=new Uri.Builder().scheme("content").authority("studio.yingxu.frame.pictures").appendPath(f.getName()).build();
                Intent send=new Intent(Intent.ACTION_SEND).setType(PictureProvider.mime(f)).putExtra(Intent.EXTRA_STREAM,uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri("图片",uri));runOnUiThread(()->{try{startActivity(Intent.createChooser(send,"分享原图"));}catch(ActivityNotFoundException ex){notifyUI("设备没有可用的分享应用");}});return true;
            }
            case "open": {
                String page=d.getString("page");
                String url;
                if("usage".equals(page))url="https://console.volcengine.com/finance/bill/detail/";
                else if("request-usage".equals(page))url="https://docs.volcengine.com/docs/ark/get-inference-request-usage-api";
                else if("tos".equals(page))url="https://docs.volcengine.com/docs/torch-object-storage/creating-data-subscription-rules";
                else if(Arrays.asList("apikey","docs/model-pricing","docs/image-generation-api","docs/models-api","docs/error-codes","docs/image-generation-tutorial","docs/log-collection","openManage").contains(page))
                    url="https://console.volcengine.com/ark/region:cn-beijing/"+page;
                else throw new IOException("链接不可用");
                Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(url));
                runOnUiThread(()->{try{startActivity(open);}catch(ActivityNotFoundException e){notifyUI("请安装可打开网页的浏览器");}});return true;
            }
            default:throw new IOException("操作不可用");
        }
    }
    private int deleteJobs(JSONArray ids) {
        int count=0;
        for(int i=0;i<ids.length();i++) {
            String id=ids.optString(i);
            if(!Studio.safe(id))continue;
            JSONObject job=app.db.find(id);
            if(job==null)continue;
            int removed=app.db.getWritableDatabase().delete("jobs","id=? AND state NOT IN ('queued','sending','fetching')",new String[]{id});
            if(removed==0)continue;
            count++;
            JSONArray images=job.optJSONObject("result").optJSONArray("images");
            for(int k=0;images!=null && k<images.length();k++) {
                String name=images.optJSONObject(k).optString("file");
                if(!Studio.safe(name))continue;
                new File(app.images,name).delete();
                new File(app.images,name+".thumb").delete();
                new File(new File(app.getFilesDir(),"viewed"),name).delete();
            }
        }
        if(count>0){app.db.revision.incrementAndGet();pruneRefs();}
        return count;
    }
    private JSONArray referenceKeys(JSONArray refs) {
        JSONArray keys=new JSONArray();
        for(int i=0;refs!=null && i<refs.length();i++) {
            String name=refs.optString(i);
            if(!Studio.safe(name)) {
                keys.put("ref:"+name);
                continue;
            }
            String cached=referenceKeys.get(name);
            if(cached==null) {
                cached="ref:"+name;
                try(InputStream in=new FileInputStream(new File(app.refs,name))) {
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    byte[] bytes=new byte[32768];
                    for(int read;(read=in.read(bytes))!=-1;)digest.update(bytes,0,read);
                    cached="sha256:"+android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
                } catch(IOException | java.security.NoSuchAlgorithmException ignored) {}
                referenceKeys.put(name,cached);
            }
            keys.put(cached);
        }
        return keys;
    }
    private void openPicker(int request,String context,boolean multiple) {
        picker=context;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple);
        try {startActivityForResult(intent,request);}
        catch(ActivityNotFoundException e){notifyUI("设备没有可用的图片选择器");}
    }
    private boolean canRestore(JSONObject job) {
        return Arrays.asList("uncertain","recovered").contains(job.optString("state")) ||
            "partial".equals(job.optString("state")) && job.optJSONObject("result").optBoolean("incomplete");
    }
    private void restorePhoto(String id,Uri uri) throws Exception {
        JSONObject job=app.db.find(id);
        if(job==null||!canRestore(job))throw new IOException("任务已删除或状态已变化");
        String name=id+"-restored-"+Studio.id();
        File file=new File(app.images,name),thumb=new File(app.images,name+".thumb");
        try {
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("无法打开图片");
                Ark.copy(in,out,120L*1024*1024);
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.toString(),bounds);
            if(bounds.outWidth<1||bounds.outHeight<1)throw new IOException("文件无法识别为图片");
            PictureProvider.thumbnail(file,thumb);
            JSONObject result=job.getJSONObject("result");
            result.put("incomplete",true);
            JSONArray images=result.optJSONArray("images");
            if(images==null){images=new JSONArray();result.put("images",images);}
            images.put(new JSONObject().put("file",name).put("ready",true).put("imported",true)
                .put("size",bounds.outWidth+"x"+bounds.outHeight));
            app.db.update(id,"recovered",result);
        } catch(Exception e) {
            file.delete();thumb.delete();throw e;
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
        if(result!=RESULT_OK || data==null)return;
        if(request==42 && data.getData()!=null) {
            String id=picker;picker="";
            io.execute(()->{
                try {restorePhoto(id,data.getData());js("window.restoredPhoto()");}
                catch(Exception e){notifyUI(ArkError.explain(e));}
            });
            return;
        }
        if(request!=41)return;
        picker="";List<Uri> uris=new ArrayList<>();ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<Math.min(14,clip.getItemCount());i++)uris.add(clip.getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        io.execute(()->{
            JSONArray names=new JSONArray(); int failed=0;
            for(Uri uri:uris) {
                try{names.put(importRef(ImageDecoder.createSource(getContentResolver(),uri)));}
                catch(Exception e){failed++;}
                finally{
                    if(PictureProvider.isCapture(uri))PictureProvider.captureFile(this,uri).delete();
                }
            }
            try {
                JSONArray pending=new JSONArray(app.prefs.getString("pendingRefs","[]"));
                for(int k=0;k<names.length();k++)pending.put(names.get(k));
                app.prefs.edit().putString("pendingRefs",pending.toString()).commit();
            } catch(JSONException ignored) {}
            final int failures=failed;
            js("window.receiveReferences && window.receiveReferences("+failures+")");
        });
    }
    private String importRef(ImageDecoder.Source source)throws Exception {
        // Normalise orientation and constrain memory, upload size and reference geometry.
        Bitmap b=ImageDecoder.decodeBitmap(source,(decoder,info,src)->{
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
