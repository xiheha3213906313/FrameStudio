package studio.yingxu.frame;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AlbumActivity extends Activity {
    static final String LIMIT = "referenceLimit";
    private static final int PERMISSION = 201;
    private static final int SYSTEM_PICKER = 202;
    private static final int PAPER = 0xfff8f7f3;
    private static final int INK = 0xff242821;
    private static final int CLAY = 0xffa7442b;
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(3);
    private final LruCache<Uri,Bitmap> cache = new LruCache<Uri,Bitmap>(10*1024*1024) {
        @Override protected int sizeOf(Uri key,Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final Map<String,List<Photo>> albums = new LinkedHashMap<>();
    private final List<Photo> photos = new ArrayList<>();
    private GridView grid;
    private Spinner folders;
    private TextView message;
    private Button done;
    private Button access;
    private PhotoAdapter adapter;
    private int limit;
    private boolean stopped;
    private int loadRevision;

    private static class Photo {
        final Uri uri;
        final String label;
        Photo(Uri uri,String label) { this.uri=uri; this.label=label; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        limit=Math.max(1,Math.min(14,getIntent().getIntExtra(LIMIT,1)));
        if(state!=null) {
            ArrayList<String> saved=state.getStringArrayList("selected");
            if(saved!=null)for(String value:saved)selected.add(Uri.parse(value));
        }
        buildLayout();
        if(!hasAccess() && state==null)askAccess();
    }

    @Override protected void onStart() {
        super.onStart();
        stopped=false;
        load();
    }

    @Override protected void onStop() {
        stopped=true;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> values=new ArrayList<>();
        for(Uri uri:selected)values.add(uri.toString());
        state.putStringArrayList("selected",values);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        catalog.shutdownNow();
        thumbnails.shutdownNow();
        super.onDestroy();
    }

    private void buildLayout() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setPadding(dp(12),0,dp(12),dp(12));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel=new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v->finish());
        header.addView(cancel);
        TextView title=new TextView(this);
        title.setText("选择参考图");
        title.setGravity(Gravity.CENTER);
        title.setTextSize(18);
        title.setTextColor(INK);
        title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(54),1));
        done=new Button(this);
        done.setTextColor(Color.WHITE);
        done.setOnClickListener(v->returnSelection(new ArrayList<>(selected)));
        header.addView(done);
        root.addView(header);

        folders=new Spinner(this);
        root.addView(folders,new LinearLayout.LayoutParams(-1,dp(46)));
        message=new TextView(this);
        message.setTextSize(14);
        message.setTextColor(INK);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(12),dp(10),dp(12),dp(10));
        root.addView(message,new LinearLayout.LayoutParams(-1,-2));
        access=new Button(this);
        access.setText("允许访问相册");
        access.setOnClickListener(v->accessAction());
        root.addView(access,new LinearLayout.LayoutParams(-1,-2));

        grid=new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(100));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        adapter=new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,child,position,id)->{
            Uri uri=photos.get(position).uri;
            if(!selected.remove(uri)) {
                if(selected.size()>=limit) {
                    Toast.makeText(this,"最多选择 "+limit+" 张",Toast.LENGTH_SHORT).show();
                    return;
                }
                selected.add(uri);
            }
            updateCount();
        });
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        updateCount();
    }

    private String imagePermission() {
        return Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean fullAccess() {
        return checkSelfPermission(imagePermission())==PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAccess() {
        return fullAccess() || Build.VERSION.SDK_INT>=34
            && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;
    }

    private void askAccess() {
        String[] permissions=Build.VERSION.SDK_INT>=34
            ? new String[]{imagePermission(),Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
            : new String[]{imagePermission()};
        requestPermissions(permissions,PERMISSION);
    }

    private void accessAction() {
        if(hasAccess() || shouldShowRequestPermissionRationale(imagePermission())) {
            askAccess();
            return;
        }
        Intent picker=new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        if(limit>1)picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,limit);
        try { startActivityForResult(picker,SYSTEM_PICKER); }
        catch(ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null)));
        }
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==PERMISSION)load();
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=SYSTEM_PICKER || result!=RESULT_OK || data==null)return;
        List<Uri> uris=new ArrayList<>();
        ClipData clip=data.getClipData();
        if(clip!=null)for(int i=0;i<Math.min(limit,clip.getItemCount());i++)uris.add(clip.getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        returnSelection(uris);
    }

    private void returnSelection(List<Uri> uris) {
        if(uris.isEmpty())return;
        ClipData clip=ClipData.newUri(getContentResolver(),"参考图",uris.get(0));
        for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
        Intent result=new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        result.setClipData(clip);
        setResult(RESULT_OK,result);
        finish();
    }

    private void load() {
        int revision=++loadRevision;
        boolean permitted=hasAccess();
        folders.setVisibility(View.GONE);
        access.setVisibility(fullAccess()?View.GONE:View.VISIBLE);
        access.setText(permitted?"选择更多照片":"相册权限与照片选择");
        message.setText(permitted?"正在读取相册…":"允许访问照片后，可以按相册浏览并多选。");
        message.setVisibility(View.VISIBLE);
        if(!permitted) {
            albums.clear();
            photos.clear();
            selected.clear();
            updateCount();
            return;
        }
        catalog.execute(()->{
            Map<String,List<Photo>> result=new LinkedHashMap<>();
            List<Photo> recent=new ArrayList<>();
            result.put("",recent);
            Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            String[] columns={MediaStore.Images.Media._ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.Images.Media.DISPLAY_NAME};
            boolean failed=false;
            try(Cursor cursor=getContentResolver().query(collection,columns,null,null,MediaStore.Images.Media._ID+" DESC")) {
                while(cursor!=null && cursor.moveToNext() && !Thread.currentThread().isInterrupted()) {
                    Photo photo=new Photo(ContentUris.withAppendedId(collection,cursor.getLong(0)),cursor.getString(2));
                    recent.add(photo);
                    String folder=cursor.getString(1);
                    if(folder!=null&&!folder.isEmpty())result.computeIfAbsent(folder,k->new ArrayList<>()).add(photo);
                }
            } catch(RuntimeException e) { failed=true; }
            final boolean queryFailed=failed;
            runOnUiThread(()->{
                if(stopped || isDestroyed() || revision!=loadRevision)return;
                albums.clear();
                albums.putAll(result);
                Set<Uri> available=new LinkedHashSet<>();
                for(Photo photo:recent)available.add(photo.uri);
                selected.retainAll(available);
                List<String> names=new ArrayList<>(result.keySet());
                List<String> labels=new ArrayList<>(names);
                labels.set(0,"所有照片");
                ArrayAdapter<String> foldersAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
                foldersAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                folders.setAdapter(foldersAdapter);
                folders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                    @Override public void onItemSelected(AdapterView<?> parent,View child,int position,long id) {
                        showAlbum(names.get(position));
                        grid.setSelection(0);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
                folders.setVisibility(recent.isEmpty()?View.GONE:View.VISIBLE);
                message.setText(queryFailed?"暂时无法读取相册":"相册中暂无可选照片");
                message.setVisibility(recent.isEmpty()?View.VISIBLE:View.GONE);
                showAlbum("");
            });
        });
    }

    private void showAlbum(String key) {
        photos.clear();
        photos.addAll(albums.getOrDefault(key,Collections.emptyList()));
        updateCount();
    }

    private void updateCount() {
        done.setText(selected.isEmpty()?"添加":"添加 "+selected.size());
        done.setEnabled(!selected.isEmpty());
        done.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected.isEmpty()?0xffa5a49a:CLAY));
        adapter.notifyDataSetChanged();
    }

    private int dp(float size) {
        return Math.round(size*getResources().getDisplayMetrics().density);
    }

    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Photo getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            PhotoCell cell=recycled instanceof PhotoCell?(PhotoCell)recycled:new PhotoCell();
            cell.bind(getItem(position));
            return cell;
        }
    }

    private class PhotoCell extends FrameLayout {
        final ImageView image;
        final TextView check;
        Uri bound;
        Future<?> pending;

        PhotoCell() {
            super(AlbumActivity.this);
            setBackgroundColor(0xffeae8de);
            image=new ImageView(AlbumActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image,new FrameLayout.LayoutParams(-1,-1));
            check=new TextView(AlbumActivity.this);
            check.setGravity(Gravity.CENTER);
            check.setTextColor(Color.WHITE);
            check.setTextSize(15);
            FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.TOP|Gravity.END);
            marker.setMargins(dp(6),dp(6),dp(6),dp(6));
            addView(check,marker);
        }

        @Override protected void onMeasure(int width,int height) {
            super.onMeasure(width,MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width),MeasureSpec.EXACTLY));
        }

        void bind(Photo photo) {
            boolean chosen=selected.contains(photo.uri);
            setContentDescription((photo.label==null?"照片":photo.label)+(chosen?"，已选":""));
            check.setText(chosen?String.valueOf(new ArrayList<>(selected).indexOf(photo.uri)+1):"");
            GradientDrawable badge=new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(chosen?CLAY:0x55000000);
            badge.setStroke(dp(1),Color.WHITE);
            check.setBackground(badge);
            if(photo.uri.equals(bound))return;
            bound=photo.uri;
            if(pending!=null)pending.cancel(false);
            Bitmap loaded=cache.get(photo.uri);
            image.setImageBitmap(loaded);
            if(loaded!=null)return;
            pending=thumbnails.submit(()->{
                try {
                    Bitmap preview=getContentResolver().loadThumbnail(photo.uri,new Size(dp(112),dp(112)),null);
                    cache.put(photo.uri,preview);
                    runOnUiThread(()->{if(!isDestroyed() && photo.uri.equals(bound))image.setImageBitmap(preview);});
                } catch(IOException | SecurityException ignored) {}
            });
        }
    }
}
