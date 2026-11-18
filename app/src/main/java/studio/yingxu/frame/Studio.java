package studio.yingxu.frame;

import android.app.Application;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public class Studio extends Application {
    Store db;
    SharedPreferences prefs;
    File images, refs;
    static final String BASE = "https://ark.cn-beijing.volces.com/api/v3";
    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("studio", MODE_PRIVATE);
        db = new Store(this);
        images = new File(getFilesDir(), "images");
        refs = new File(getFilesDir(), "references");
        images.mkdirs(); refs.mkdirs();
        // The synchronous POST may already have been billed. Only its downloads are safe to replay.
        db.getWritableDatabase().execSQL("UPDATE jobs SET state='uncertain' WHERE state='sending'");
    }
    void wake() { startForegroundService(new Intent(this, ImageService.class)); }
    static String id() { return UUID.randomUUID().toString(); }
    static boolean safe(String name) { return name != null && name.matches("[A-Za-z0-9_.-]{1,110}") && !name.contains(".."); }
    static JSONObject json(String data) {
        try { return new JSONObject(data); } catch (Exception e) { return new JSONObject(); }
    }
    static JSONObject with(JSONObject object, String key, Object value) {
        try { object.put(key, value); return object; } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    synchronized String key() throws Exception {
        String data = prefs.getString("secret", "");
        if (data.isEmpty()) return "";
        String[] parts = data.split("\\.");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, store.getKey("frame-api", null), new GCMParameterSpec(128, Base64.decode(parts[0], 0)));
        return new String(cipher.doFinal(Base64.decode(parts[1], 0)), "UTF-8");
    }
    synchronized void setKey(String key) throws Exception {
        SharedPreferences.Editor edit = prefs.edit().remove("models").remove("synced");
        if (key.isEmpty()) { edit.remove("secret").commit(); return; }
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("frame-api")) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("frame-api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, ks.getKey("frame-api", null));
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(key.getBytes("UTF-8")), Base64.NO_WRAP);
        if (!edit.putString("secret", encoded).commit()) throw new IOException("无法保存密钥");
    }
}

class Store extends SQLiteOpenHelper {
    final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
    Store(Context c) { super(c, "studio.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,created INTEGER NOT NULL,state TEXT NOT NULL,body TEXT NOT NULL,result TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int a, int b) { }
    void add(String id, JSONObject body) {
        ContentValues row = new ContentValues();
        row.put("id", id); row.put("created", System.currentTimeMillis()); row.put("state", "queued");
        row.put("body", body.toString()); row.put("result", "{}");
        getWritableDatabase().insertOrThrow("jobs", null, row);
        revision.incrementAndGet();
    }
    void update(String id, String state, JSONObject result) {
        ContentValues row = new ContentValues(); row.put("state", state); row.put("result", result.toString());
        getWritableDatabase().update("jobs", row, "id=?", new String[]{id});
        revision.incrementAndGet();
    }
    boolean transition(String id, String from, String to) {
        ContentValues row = new ContentValues(); row.put("state", to);
        boolean changed=getWritableDatabase().update("jobs", row, "id=? AND state=?", new String[]{id,from}) == 1;
        if(changed)revision.incrementAndGet();
        return changed;
    }
    private JSONObject read(Cursor c) {
        JSONObject j = new JSONObject();
        Studio.with(j,"id",c.getString(0)); Studio.with(j,"created",c.getLong(1)); Studio.with(j,"state",c.getString(2));
        Studio.with(j,"body",Studio.json(c.getString(3))); Studio.with(j,"result",Studio.json(c.getString(4)));
        return j;
    }
    JSONArray all() {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM jobs ORDER BY created DESC,rowid DESC", null)) {
            while(c.moveToNext()) rows.put(read(c));
        }
        return rows;
    }
    JSONObject next() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM jobs WHERE state IN ('queued','fetching') ORDER BY created,rowid LIMIT 1", null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }
    boolean busy() {
        try(Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM jobs WHERE state IN ('queued','sending','fetching') LIMIT 1",null)) { return c.moveToFirst(); }
    }
}
