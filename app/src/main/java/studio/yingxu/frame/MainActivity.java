package studio.yingxu.frame;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final ExecutorService macNetwork=Executors.newSingleThreadExecutor();
    private String picker="";
    private boolean ready, showTasks, watchingMacNetwork;
    private boolean macReachable;
    private MacDiscovery macDiscovery;
    private final Runnable refreshMacNetwork=()->{
        macDiscovery.restart();
        if(ready)js("window.macNetworkChanged && window.macNetworkChanged()");
    };
    private final ConnectivityManager.NetworkCallback macNetworkChanges=new ConnectivityManager.NetworkCallback(){
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties link){scheduleMacNetworkRefresh();}
        @Override public void onLost(Network network){scheduleMacNetworkRefresh();}
    };
    private boolean keyboardVisible;
    private int statusInset;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayout;
    private final Map<String,String> referenceKeys=new HashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app=(Studio)getApplication();
        macDiscovery=new MacDiscovery(this,this::sendMacServices);
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
                sendMotionPreference();
                sendMacServices();
                if(showTasks){js("window.navigate('tasks')");showTasks=false;}
            }
        });
        view.addJavascriptInterface(new Bridge(),"StudioNative");setContentView(view);
        observeInsets();
        view.loadUrl(HOME);
        ModelSync.schedule(app);
        app.db.expediteWaitingMac();
        if(app.db.next()!=null || app.db.nextMacAt()!=Long.MAX_VALUE) app.wake();
        io.execute(()->pruneRefs());
    }
    private void pruneRefs() {
        Set<String> used=new HashSet<>();
        JSONArray tasks=app.db.all();
        for(int i=0;i<tasks.length();i++) {
            JSONObject body=tasks.optJSONObject(i).optJSONObject("body");
            collect(used,body.optJSONArray("refs"));
            collect(used,body.optJSONArray("macInputs"));
            if(body.has("macRequest"))used.add(body.optString("macRequest"));
        }
        collect(used,Studio.json(app.prefs.getString("draft","{}")).optJSONArray("refs"));
        File[] files=app.refs.listFiles();
        if(files!=null) for(File f:files) {
            String name=f.getName().replaceFirst("[.]thumb$", "");
            String owner=name.replaceFirst("[.]original$", "");
            if(!used.contains(name) && !used.contains(owner) && System.currentTimeMillis()-f.lastModified()>86400000L) {
                f.delete();
                if(name.equals(owner))app.prefs.edit().remove("source."+owner).apply();
            }
        }
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
    private void sendMotionPreference() {
        js("window.setReducedMotion && window.setReducedMotion("+!android.animation.ValueAnimator.areAnimatorsEnabled()+")");
    }
    private void setKeyboardVisible(boolean visible) {
        if(keyboardVisible && !visible) {
            view.postDelayed(()->{
                if(!keyboardVisible && !isDestroyed())js("window.blurInputs && window.blurInputs()");
            },80);
        }
        keyboardVisible=visible;
    }
    @Override protected void onStart() {
        super.onStart();
        macDiscovery.start();
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);
        if(manager==null)return;
        NetworkRequest localNetworks=new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build();
        manager.registerNetworkCallback(localNetworks,macNetworkChanges,new Handler(Looper.getMainLooper()));
        watchingMacNetwork=true;
    }

    private void scheduleMacNetworkRefresh(){
        if(!watchingMacNetwork)return;
        view.removeCallbacks(refreshMacNetwork);
        view.postDelayed(refreshMacNetwork,400);
    }

    @Override protected void onResume() {
        super.onResume();
        DisplayPolicy.preferHighRefreshRate(this,view);
        if(ready){
            sendMotionPreference();
            js("window.macNetworkChanged && window.macNetworkChanged()");
        }
    }

    @Override protected void onStop(){
        if(watchingMacNetwork){
            watchingMacNetwork=false;
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(macNetworkChanges);
        }
        view.removeCallbacks(refreshMacNetwork);
        macDiscovery.stop();
        super.onStop();
    }
    @Override public void onDestroy(){
        ready=false;
        io.shutdown();
        network.shutdown();
        macNetwork.shutdown();
        if(keyboardLayout!=null)view.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayout);
        view.removeJavascriptInterface("StudioNative");
        view.destroy();
        super.onDestroy();
    }
    private void js(String script){runOnUiThread(()->{if(!isDestroyed() && !isFinishing()) view.evaluateJavascript(script,null);});}
    private void reply(String id,boolean ok,Object value){
        js("window.nativeReply("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(String.valueOf(value))+")");
    }
    private void sendMacServices() {
        if(ready)js("window.macServicesChanged && window.macServicesChanged("+macDiscovery.json()+")");
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
            boolean macCall=Arrays.asList("macPairInspect","macPairConfirm","macPairCancel","macPair","macRefresh","macAddress","macForget").contains(action);
            ExecutorService pool=macCall?macNetwork:"sync".equals(action)?network:io;
            pool.execute(()->{
                try { Object answer=handle(action,Studio.json(raw));reply(id,true,answer); }
                catch(Exception e){reply(id,false,macCall?MacTransport.explain(e):ArkError.explain(e));}
            });
        }
    }
    private Object handle(String action,JSONObject d) throws Exception {
        switch(action) {
            case "init": return new JSONObject().put("hasKey",app.prefs.contains("secret"))
                .put("models",new JSONArray(app.prefs.getString("models","[]"))).put("synced",app.prefs.getLong("synced",0))
                .put("custom",new JSONArray(app.prefs.getString("custom","[]"))).put("draft",Studio.json(app.prefs.getString("draft","{}")))
                .put("mac",app.mac.status()).put("macServices",macDiscovery.json());
            case "draft": app.prefs.edit().putString("draft",d.toString()).apply();return true;
            case "key":
                if(app.db.busy()) throw new IOException("请等待当前任务结束后再更换密钥");
                String key=d.optString("key").trim();
                if(key.length()>2048 || key.matches("(?s).*\\s.*"))throw new IOException("密钥中含有空白或长度异常");
                app.setKey(key);ModelSync.schedule(app);return true;
            case "sync": return ModelSync.sync(app,d.optBoolean("manual"));
            case "macDiscover":
                runOnUiThread(()->{if(watchingMacNetwork)macDiscovery.restart();});
                return true;
            case "macPairInspect": return app.mac.inspectPairing(d.getString("endpoint"));
            case "macPairConfirm": return app.mac.confirmPairing(d.getString("attemptId"));
            case "macPairCancel": app.mac.cancelPairing(d.getString("attemptId"));return true;
            case "macPair": {
                try {
                    JSONObject status=app.mac.pair(d.getString("attemptId"),d.getString("code"));
                    macReachable=false;
                    return status;
                } catch(MacTransport.Failure error) {
                    if(!MacPairing.locked(error))throw error;
                    return new JSONObject().put("pairingLocked",true).put("message",MacPairing.LOCKED_MESSAGE);
                }
            }
            case "macRefresh":
            case "macAddress": {
                String previous=app.mac.status().optString("endpoint");
                JSONObject status;
                try {status=app.mac.refresh("macAddress".equals(action)?d.getString("endpoint"):null,macDiscovery.snapshot());}
                catch(Exception error){macReachable=false;throw error;}
                boolean reconnected=!macReachable||!previous.equals(status.optString("endpoint"));
                macReachable=true;
                MacConnection.Session session=app.mac.session();
                if(session!=null && app.db.resumeMac(session.serverId,session.deviceId,reconnected))app.wake();
                return status;
            }
            case "macForget":
                app.mac.forget();macReachable=false;return app.mac.status();
            case "macImage": return macImage(d);
            case "macMask": {
                JSONObject job=app.db.find(d.getString("id"));
                if(job==null||!"inpaint".equals(job.getJSONObject("body").optString("operation")))
                    throw new IOException("局部编辑任务不可用。");
                return new JSONObject(MacJobs.readSnapshot(app,job.getJSONObject("body"))).getJSONArray("strokes");
            }
            case "macReferenceSize": {
                int[] size=MacImages.dimensions(MacJobs.input(app,d.getString("ref")),null);
                return new JSONObject().put("width",size[0]).put("height",size[1]);
            }
            case "macPick":
                runOnUiThread(()->openPicker(43,"macUpscale",false));
                return true;
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
            case "editPhoto": {
                File work=fileFrom(d);
                String ref=importRef(ImageDecoder.createSource(work));
                try(InputStream in=new FileInputStream(work)) {
                    MacImages.snapshot(in,new File(app.refs,ref+".original"));
                } catch(IOException ignored) {}
                return ref;
            }
            case "custom": app.prefs.edit().putString("custom",d.getJSONArray("list").toString()).commit();return true;
            case "jobs": {
                long rev=app.db.revision.get();
                if(d.optLong("since",-1)==rev)return JSONObject.NULL;
                JSONArray rows=app.db.all();
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);
                    JSONObject body=row.getJSONObject("body");
                    body.remove("macRequest");
                    if(!MacJobs.isMac(body) && Arrays.asList("failed","not_sent","uncertain","download_error").contains(row.optString("state")))
                        row.put("refKeys",referenceKeys(body.optJSONArray("refs")));
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
                JSONObject b=d.getJSONObject("body");
                if(MacJobs.isMac(b)) {
                    String id=MacJobs.enqueue(app,b);
                    app.wake();
                    return id;
                }
                if(app.key().isEmpty()) throw new IOException("请先连接方舟 API Key");
                String id=d.getString("id");
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
                PromptReferences.serialize(b,b.getJSONArray("refs"),false);
                if(separate && count>1) app.db.addCopies(id,b,count);
                else app.db.add(id,b);
                app.wake();return id;
            }
            case "cancel": {
                String id=d.getString("id");
                JSONObject job=app.db.find(id);
                if(job!=null && MacJobs.isMac(job.getJSONObject("body"))) {
                    if(!app.db.requestMacCancel(id))return false;
                    app.wake();return true;
                }
                return app.db.transition(id,"queued","cancelled");
            }
            case "recover": {
                String id=d.getString("id");
                JSONObject job=app.db.find(id);
                boolean ready=job!=null && (MacJobs.isMac(job.getJSONObject("body"))?app.db.recoverMac(id):
                    app.db.transition(id,"download_error","fetching"));
                if(ready)app.wake();
                return ready;
            }
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
                String removed=app.prefs.getString("pendingRemoved","[]");
                String order=app.prefs.getString("pendingRefOrder","[]");
                app.prefs.edit().remove("pendingRefs").remove("pendingRemoved").remove("pendingRefOrder").apply();
                return new JSONObject().put("added",new JSONArray(pending)).put("removed",new JSONArray(removed)).put("order",new JSONArray(order));
            }
            case "pick": {
                int limit=Math.max(0,Math.min(14,d.optInt("limit",1)));
                JSONArray draftRefs=Studio.json(app.prefs.getString("draft","{}")).optJSONArray("refs");
                if(draftRefs==null)draftRefs=new JSONArray();
                ArrayList<String> sources=new ArrayList<>(),files=new ArrayList<>();
                for(int i=0;i<draftRefs.length();i++){
                    String file=draftRefs.optString(i);
                    String uri=app.prefs.getString("source."+file,null);
                    if(!Studio.safe(file))continue;
                    if(uri==null || sources.contains(uri))uri="content://studio.yingxu.frame.pictures/reference/"+file;
                    sources.add(uri);files.add(file);
                }
                Intent pick=new Intent(this,AlbumActivity.class)
                    .putExtra(AlbumActivity.LIMIT,Math.min(14,limit+sources.size()))
                    .putExtra(AlbumActivity.DRAFT,draftRefs.toString())
                    .putExtra(AlbumActivity.UPSCALE_AVAILABLE,app.mac.status().has("endpoint"))
                    .putStringArrayListExtra(AlbumActivity.EXISTING,sources)
                    .putStringArrayListExtra(AlbumActivity.FILES,files);
                runOnUiThread(()->startActivityForResult(pick,41));
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
            int removed=app.db.getWritableDatabase().delete("jobs","id=? AND state NOT IN "+Store.ALL_ACTIVE,new String[]{id});
            if(removed==0)continue;
            count++;
            if(MacJobs.isMac(job.optJSONObject("body"))) {
                for(String suffix:Arrays.asList("-0","-0.part","-0.thumb","-0.thumb.part"))
                    new File(app.images,id+suffix).delete();
            }
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
        if((request==43 || request==41 && data.getBooleanExtra(AlbumActivity.UPSCALE,false)) && data.getData()!=null) {
            picker="";
            Uri uri=data.getData();
            io.execute(()->{
                try {
                    String ref=data.getStringExtra("macRef");
                    JSONObject source=ref==null?snapshotForMac(uri):macImage(new JSONObject().put("ref",ref));
                    js("window.receiveUpscale && window.receiveUpscale("+source+")");
                }
                catch(Exception e){notifyUI(MacTransport.explain(e));}
                finally {if(PictureProvider.isCapture(uri))PictureProvider.captureFile(this,uri).delete();}
            });
            return;
        }
        if(request!=41)return;
        picker="";List<Uri> uris=new ArrayList<>();ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<Math.min(14,clip.getItemCount());i++)uris.add(clip.getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        io.execute(()->{
            JSONArray names=new JSONArray(); int failed=0;
            Map<String,String> imported=new HashMap<>();
            for(Uri uri:uris) {
                try{
                    String name=importRef(ImageDecoder.createSource(getContentResolver(),uri));
                    names.put(name);
                    imported.put(uri.toString(),name);
                    app.prefs.edit().putString("source."+name,uri.toString()).apply();
                    try { cacheOriginal(name,uri); } catch(Exception ignored) {}
                }
                catch(Exception e){failed++;}
                finally{
                    if(PictureProvider.isCapture(uri))PictureProvider.captureFile(this,uri).delete();
                }
            }
            try {
                JSONArray pending=new JSONArray(app.prefs.getString("pendingRefs","[]"));
                for(int k=0;k<names.length();k++)pending.put(names.get(k));
                JSONArray removed=new JSONArray(app.prefs.getString("pendingRemoved","[]"));
                ArrayList<String> changes=data.getStringArrayListExtra(AlbumActivity.REMOVED);
                if(changes!=null)for(String name:changes)removed.put(name);
                JSONArray order=new JSONArray();
                ArrayList<String> selected=data.getStringArrayListExtra(AlbumActivity.ORDER);
                if(selected!=null)for(String item:selected){
                    String name=Studio.safe(item)?item:imported.get(item);
                    if(name!=null)order.put(name);
                }
                app.prefs.edit().putString("pendingRefs",pending.toString()).putString("pendingRemoved",removed.toString())
                    .putString("pendingRefOrder",order.toString()).commit();
            } catch(JSONException ignored) {}
            final int failures=failed;
            js("window.receiveReferences && window.receiveReferences("+failures+")");
        });
    }
    private void cacheOriginal(String ref, Uri uri) throws IOException {
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("相册原图不可用，请重新选择。");
            MacImages.snapshot(input,new File(app.refs,ref+".original"));
        } catch(SecurityException e) {throw new IOException("已失去相册原图读取权限，请重新选择。",e);}
    }

    private JSONObject macImage(JSONObject data) throws Exception {
        if(data.has("ref")) {
            String ref=data.getString("ref");
            MacJobs.input(app,ref);
            if(!ref.endsWith(".source")&&!new File(app.refs,ref+".original").isFile()) {
                String uri=app.prefs.getString("source."+ref,"");
                if(uri.isEmpty())throw new IOException("原图快照不可用，请从相册重新选择原图。");
                cacheOriginal(ref,Uri.parse(uri));
            }
            return describeMacImage(ref,MacJobs.original(app,ref));
        }
        try(InputStream input=new FileInputStream(fileFrom(data))) {return snapshotForMac(input);}
    }

    private JSONObject snapshotForMac(Uri uri) throws Exception {
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("相册原图不可用，请重新选择。");
            return snapshotForMac(input);
        } catch(SecurityException e) {throw new IOException("已失去相册原图读取权限，请重新选择。",e);}
    }

    private JSONObject snapshotForMac(InputStream input) throws Exception {
        String ref=Studio.id()+".source";
        File file=new File(app.refs,ref);
        try {
            MacImages.snapshot(input,file);
            return describeMacImage(ref,ref);
        } catch(Exception error) {
            file.delete();
            new File(app.refs,ref+".thumb").delete();
            throw error;
        }
    }

    private JSONObject describeMacImage(String ref,String original) throws Exception {
        File source=MacJobs.input(app,original);
        int[] size=MacImages.dimensions(source,null);
        File preview=new File(app.refs,original+".thumb");
        if(!preview.isFile())MacImages.preview(source,preview);
        return new JSONObject().put("ref",ref).put("preview",preview.getName()).put("width",size[0]).put("height",size[1]);
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
