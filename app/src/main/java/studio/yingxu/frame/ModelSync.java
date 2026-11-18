package studio.yingxu.frame;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

public class ModelSync extends JobService {
    private static final long DAY=86400000L;
    private static final int JOB=101;
    private static final Object LOCK=new Object();
    private volatile JobParameters running;

    static void schedule(Studio app) {
        JobScheduler scheduler=app.getSystemService(JobScheduler.class);
        if(!app.prefs.contains("secret")) {
            scheduler.cancel(JOB);
        } else if(scheduler.getPendingJob(JOB)==null) {
            scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(app,ModelSync.class))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).build());
        }
    }

    static JSONObject sync(Studio app,boolean manual) throws Exception {
        synchronized(LOCK) {
            if(manual || System.currentTimeMillis()-app.prefs.getLong("synced",0)>=DAY) {
                String key=app.key();
                if(key.isEmpty())return cache(app);
                JSONArray models=Ark.models(key);
                synchronized(app) {
                    if(!key.equals(app.key()))throw new IOException("密钥已更换，请重新同步");
                    app.prefs.edit().putString("models",models.toString()).putLong("synced",System.currentTimeMillis()).apply();
                }
            }
            return cache(app);
        }
    }

    static JSONObject cache(Studio app) throws Exception {
        synchronized(app) {
            return new JSONObject().put("models",new JSONArray(app.prefs.getString("models","[]")))
                .put("synced",app.prefs.getLong("synced",0));
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        running=params;
        new Thread(()->{
            boolean retry=false;
            try {sync((Studio)getApplication(),false);}
            catch(Exception failure){retry=!(failure instanceof Ark.Rejected) || ((Ark.Rejected)failure).status>=500;}
            if(running==params)jobFinished(params,retry);
        },"daily-model-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        running=null;
        return true;
    }
}
