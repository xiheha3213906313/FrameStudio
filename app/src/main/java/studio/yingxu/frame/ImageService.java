package studio.yingxu.frame;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public class ImageService extends Service {
    private final ExecutorService runner=Executors.newSingleThreadExecutor();
    private final ExecutorService macRunner = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::dispatch;
    private Studio app;
    private MacJobs macJobs;
    private int latestStart;
    private boolean arkRunning, macRunning, destroyed;
    @Override public void onCreate() {
        super.onCreate(); app=(Studio)getApplication(); macJobs = new MacJobs(app);
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("generation","图片生成任务",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("tasks",true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(11,new Notification.Builder(this,"generation").setContentTitle("映序 · 正在处理图片")
            .setContentText("点按查看生成任务").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(open).setOngoing(true).build());
        latestStart = startId;
        dispatch();
        return START_NOT_STICKY;
    }
    private void dispatch() {
        if (destroyed) return;
        handler.removeCallbacks(pump);
        if (!arkRunning && app.db.next() != null) {
            arkRunning = true;
            runner.execute(() -> {
                try {
                    JSONObject task;
                    while ((task = app.db.next()) != null) runTask(task);
                } finally { handler.post(() -> { arkRunning = false; dispatch(); }); }
            });
        }
        if (!macRunning) {
            long now = System.currentTimeMillis();
            JSONObject task = app.db.nextMac(now);
            if (task != null) {
                macRunning = true;
                macRunner.execute(() -> {
                    try { macJobs.run(task); }
                    finally { handler.post(() -> { macRunning = false; dispatch(); }); }
                });
            } else {
                long at = app.db.nextMacAt();
                if (at != Long.MAX_VALUE) {
                    handler.postDelayed(pump, Math.max(100, at - now));
                    return;
                }
            }
        }
        if (!arkRunning && !macRunning) stopSelfResult(latestStart);
    }
    private void runTask(JSONObject task) {
        String id=task.optString("id"); String state=task.optString("state");
        JSONObject r=task.optJSONObject("result"), b=task.optJSONObject("body");
        if ("queued".equals(state)) {
            if(!app.db.transition(id,"queued","sending")) return;
            JSONObject[] last={new JSONObject()};
            try {
                String key=app.key();
                if(key.isEmpty()) throw new Ark.Rejected(401,"请先设置 API Key");
                r=Ark.generate(app,b,key,id,checkpoint->{
                    last[0]=checkpoint;
                    app.db.update(id,"sending",checkpoint);
                });
                if(r.getJSONArray("images").length()==0) {
                    app.db.update(id,r.optBoolean("incomplete")||r.getJSONArray("errors").length()==0?"uncertain":"failed",r);
                    return;
                }
            } catch(Exception e) {
                r=last[0];
                String phase=r.optString("phase");
                String message=ArkError.explain(e);
                if(e instanceof java.net.SocketTimeoutException) {
                    if("awaiting".equals(phase))message="请求体已发出，等待方舟返回结果超时。";
                    if("response".equals(phase))message="已收到方舟响应头，读取图片结果时超时。";
                    if("uploading".equals(phase))message="发送请求体时超时。";
                }
                Studio.with(r,"message",message);
                JSONArray received=r.optJSONArray("images");
                if(received!=null && received.length()>0) {
                    Studio.with(r,"incomplete",true);
                } else {
                    int status=e instanceof Ark.Rejected?((Ark.Rejected)e).status:r.optInt("httpStatus");
                    boolean rejected=status>=400 && status<500 && status!=408;
                    boolean notSent=e instanceof Ark.NotSent || phase.isEmpty() && !rejected;
                    String stateAfterFailure=notSent?"not_sent":rejected?"failed":"uncertain";
                    app.db.update(id,stateAfterFailure,r);return;
                }
            }
            app.db.update(id,"fetching",r);
        }
        JSONArray photos=r.optJSONArray("images"); boolean missing=false;
        for(int i=0;photos!=null && i<photos.length();i++) {
            JSONObject image=photos.optJSONObject(i);
            if(image.optBoolean("ready")) continue;
            String name=image.optString("file");
            File file=new File(app.images,name), part=new File(app.images,name+".part");
            try {
                if (!file.isFile()) {
                    downloadWithRetry(image.getString("url"),part,image.optBoolean("imported"));
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(part.toString(),bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("返回文件无法识别为图片");
                    if (!part.renameTo(file)) throw new IOException("无法保存图片，请检查剩余空间");
                }
                BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(file.toString(),opt);
                if(image.optString("size").isEmpty()) image.put("size",opt.outWidth+"x"+opt.outHeight);
                PictureProvider.thumbnail(file,new File(app.images,name+".thumb"));
                image.put("ready",true); image.remove("url"); image.remove("downloadError");
            } catch(Exception e) { missing=true; Studio.with(image,"downloadError",ArkError.explain(e)); }
            finally { part.delete(); }
            app.db.update(id,"fetching",r);
        }
        boolean imported=false;
        for(int i=0;photos!=null && i<photos.length();i++)if(photos.optJSONObject(i).optBoolean("imported"))imported=true;
        boolean partial=r.optBoolean("incomplete") || r.optJSONArray("errors")!=null && r.optJSONArray("errors").length()>0;
        app.db.update(id,missing?"download_error":imported?"recovered":partial?"partial":"done",r);
    }
    private void downloadWithRetry(String url,File part,boolean fromBackup) throws Exception {
        for(int attempt=0;;attempt++) {
            try { Ark.download(url,part,fromBackup);return; }
            catch(IOException e) {
                if(attempt==2)throw e;
                if(e instanceof Ark.DownloadRejected) {
                    int status=((Ark.DownloadRejected)e).status;
                    if(status<500 && status!=408 && status!=429)throw e;
                }
                Thread.sleep(1000L<<attempt);
            }
        }
    }
    @Override public IBinder onBind(Intent i) {return null;}
    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        runner.shutdown();
        macRunner.shutdown();
        super.onDestroy();
    }
}
