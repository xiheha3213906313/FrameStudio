package studio.yingxu.frame;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.IBinder;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public class ImageService extends Service {
    private final ExecutorService runner=Executors.newSingleThreadExecutor();
    private Studio app;
    @Override public void onCreate() {
        super.onCreate(); app=(Studio)getApplication();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("generation","图片生成任务",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("tasks",true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(11,new Notification.Builder(this,"generation").setContentTitle("映序 · 正在处理图片")
            .setContentText("点按查看生成任务").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(open).setOngoing(true).build());
        runner.execute(()->{
            JSONObject task;
            while ((task=app.db.next())!=null) runTask(task);
            stopSelfResult(startId);
        });
        return START_NOT_STICKY;
    }
    private void runTask(JSONObject task) {
        String id=task.optString("id"); String state=task.optString("state");
        JSONObject r=task.optJSONObject("result"), b=task.optJSONObject("body");
        if ("queued".equals(state)) {
            if(!app.db.transition(id,"queued","sending")) return;
            try {
                String key=app.key();
                if(key.isEmpty()) throw new Ark.Rejected(401,"请先设置 API Key");
                JSONObject response=Ark.generate(app,b,key);
                r=Ark.generationResult(id,response);
                if(r.getJSONArray("images").length()==0) {
                    if(r.getJSONArray("errors").length()==0) throw new IOException("方舟响应中没有可识别的图片，请核对账单");
                    app.db.update(id,"failed",r); return;
                }
                app.db.update(id,"fetching",r);
            } catch(Exception e) {
                Studio.with(r,"message",ArkError.explain(e));
                boolean rejected=e instanceof Ark.NotSent || e instanceof Ark.Rejected && ((Ark.Rejected)e).status>=400 && ((Ark.Rejected)e).status<500 && ((Ark.Rejected)e).status!=408;
                app.db.update(id,rejected?"failed":"uncertain",r); return;
            }
        }
        JSONArray photos=r.optJSONArray("images"); boolean missing=false;
        for(int i=0;photos!=null && i<photos.length();i++) {
            JSONObject image=photos.optJSONObject(i);
            if(image.optBoolean("ready")) continue;
            String name=image.optString("file");
            File file=new File(app.images,name), part=new File(app.images,name+".part");
            try {
                if (!file.isFile()) {
                    Ark.download(image.getString("url"),part);
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
        app.db.update(id,missing?"download_error":r.optJSONArray("errors")!=null && r.optJSONArray("errors").length()>0?"partial":"done",r);
    }
    @Override public IBinder onBind(Intent i) {return null;}
    @Override public void onDestroy() {runner.shutdown();super.onDestroy();}
}
