package studio.yingxu.frame;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    static final String LIMIT="referenceLimit";
    private static final int PERMISSION=201, CAMERA=202;
    private static final int PAPER=0xfff8f7f3, INK=0xff242821, MUTED=0xff66685e;
    private static final int CLAY=0xffa7442b, PALE=0xfff2e4db, NEUTRAL=0xffeae8e0;

    private final ExecutorService catalog=Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails=Executors.newFixedThreadPool(3);
    private final LruCache<Uri,Bitmap> cache=new LruCache<Uri,Bitmap>(10*1024*1024){
        @Override protected int sizeOf(Uri key,Bitmap image){return image.getAllocationByteCount();}
    };
    private final Set<Uri> selected=new LinkedHashSet<>();
    private final Map<String,Album> albums=new LinkedHashMap<>();
    private final List<Photo> allPhotos=new ArrayList<>();
    private final List<Photo> captures=new ArrayList<>();
    private LinearLayout root,tabs,footer;
    private GridView grid;
    private TextView heading,message;
    private Button back,access,photoTab,albumTab,preview,done;
    private PhotoAdapter adapter;
    private boolean albumsTab,stopped,loaded,queryFailed,finishedSelection;
    private String albumKey="";
    private View previewPanel;
    private Future<?> previewLoad;
    private Uri cameraUri;
    private int limit,loadRevision;
    private float swipeStart;

    private static class Photo {
        final Uri uri;
        final String label;
        Photo(Uri uri,String label){this.uri=uri;this.label=label;}
    }
    private static class Album {
        final String key,name;
        final List<Photo> photos=new ArrayList<>();
        Album(String key,String name){this.key=key;this.name=name;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        limit=Math.max(1,Math.min(14,getIntent().getIntExtra(LIMIT,1)));
        if(state!=null){
            albumsTab=state.getBoolean("albumsTab");
            albumKey=state.getString("albumKey","");
            cameraUri=state.getParcelable("cameraUri");
            ArrayList<String> saved=state.getStringArrayList("selected");
            if(saved!=null)for(String value:saved)selected.add(Uri.parse(value));
            ArrayList<String> captured=state.getStringArrayList("captures");
            if(captured!=null)for(String value:captured)captures.add(new Photo(Uri.parse(value),"刚拍的照片"));
        }
        buildLayout();
        if(!hasAccess()&&state==null)askAccess();
    }
    @Override protected void onStart(){super.onStart();stopped=false;load();}
    @Override protected void onResume(){super.onResume();DisplayPolicy.preferHighRefreshRate(this,root);}
    @Override protected void onStop(){stopped=true;super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state) {
        ArrayList<String> values=new ArrayList<>();
        for(Uri uri:selected)values.add(uri.toString());
        state.putStringArrayList("selected",values);
        ArrayList<String> captured=new ArrayList<>();
        for(Photo photo:captures)captured.add(photo.uri.toString());
        state.putStringArrayList("captures",captured);
        state.putBoolean("albumsTab",albumsTab);
        state.putString("albumKey",albumKey);
        state.putParcelable("cameraUri",cameraUri);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy(){
        if(isFinishing()){
            discardCamera();
            for(Photo photo:captures)
                if(!finishedSelection||!selected.contains(photo.uri))PictureProvider.captureFile(this,photo.uri).delete();
        }
        catalog.shutdownNow();
        thumbnails.shutdownNow();
        super.onDestroy();
    }

    private void buildLayout(){
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        LinearLayout header=rowBar();
        header.setPadding(dp(14),dp(7),dp(14),dp(4));
        back=control("返回",v->onBackPressed());
        header.addView(back,new LinearLayout.LayoutParams(dp(58),dp(44)));
        heading=label("参考图",20,INK);
        heading.setTypeface(null,Typeface.BOLD);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        access=control("权限",v->accessAction());
        header.addView(access,new LinearLayout.LayoutParams(dp(58),dp(44)));
        root.addView(header);

        tabs=rowBar();
        tabs.setPadding(dp(14),dp(4),dp(14),dp(10));
        photoTab=control("全部照片",v->openTab(false));
        albumTab=control("相册",v->openTab(true));
        LinearLayout.LayoutParams photoLayout=new LinearLayout.LayoutParams(0,dp(40),1);
        photoLayout.rightMargin=dp(8);
        tabs.addView(photoTab,photoLayout);
        tabs.addView(albumTab,new LinearLayout.LayoutParams(0,dp(40),1));
        root.addView(tabs);

        message=label("",14,MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(20),dp(14),dp(20),dp(14));
        root.addView(message,new LinearLayout.LayoutParams(-1,-2));
        grid=new GridView(this);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        adapter=new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,child,position,id)->selectCell(position));
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));

        View divider=new View(this);
        divider.setBackgroundColor(0xffdedcd3);
        root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        footer=rowBar();
        footer.setPadding(dp(14),dp(10),dp(14),dp(10));
        preview=control("预览",v->showPreview(new ArrayList<>(selected),0));
        done=control("添加",v->returnSelection());
        footer.addView(preview,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams doneLayout=new LinearLayout.LayoutParams(0,dp(44),1);
        doneLayout.leftMargin=dp(10);
        footer.addView(done,doneLayout);
        root.addView(footer);
        setContentView(root);
        updateView();
    }

    private LinearLayout rowBar(){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private TextView label(String value,int textSize,int color){
        TextView text=new TextView(this);
        text.setText(value);
        text.setTextSize(textSize);
        text.setTextColor(color);
        return text;
    }
    private Button control(String text,View.OnClickListener action){
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setPadding(dp(4),0,dp(4),0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setSingleLine();
        button.setOnClickListener(action);
        skin(button,NEUTRAL,INK,12);
        return button;
    }
    private GradientDrawable shape(int color,int radius){
        GradientDrawable background=new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }
    private void skin(Button button,int bg,int fg,int radius){
        button.setBackground(shape(bg,radius));
        button.setTextColor(fg);
    }

    private boolean inAlbumList(){return albumsTab&&albumKey.isEmpty();}
    private boolean cameraTile(){return !albumsTab;}
    private List<Photo> visiblePhotos(){
        Album album=albums.get(albumKey);
        return albumsTab&&album!=null?album.photos:allPhotos;
    }
    private void openTab(boolean showAlbums){
        albumsTab=showAlbums;
        albumKey="";
        updateView();
        grid.setSelection(0);
    }
    private void updateView(){
        boolean folder=albumsTab&&!albumKey.isEmpty();
        Album current=albums.get(albumKey);
        back.setText(folder?"返回":"取消");
        heading.setText(current==null?(inAlbumList()?"本机相册":"参考图"):current.name);
        tabs.setVisibility(folder?View.GONE:View.VISIBLE);
        access.setVisibility(fullAccess()?View.INVISIBLE:View.VISIBLE);
        skin(photoTab,!albumsTab?PALE:NEUTRAL,!albumsTab?CLAY:INK,10);
        skin(albumTab,albumsTab?PALE:NEUTRAL,albumsTab?CLAY:INK,10);

        boolean covers=inAlbumList();
        grid.setNumColumns(covers?3:4);
        grid.setPadding(dp(covers?14:0),0,dp(covers?14:0),0);
        grid.setHorizontalSpacing(dp(covers?9:3));
        grid.setVerticalSpacing(dp(covers?10:3));
        String hint=!hasAccess()?"允许访问照片后即可浏览本机相册。":!loaded?"正在读取照片…":
            queryFailed?"暂时无法读取本机相册":covers&&albums.isEmpty()?"暂无照片相册":
            visiblePhotos().isEmpty()?"这里还没有照片":"";
        message.setText(hint);
        message.setVisibility(hint.isEmpty()?View.GONE:View.VISIBLE);
        updateCount();
    }
    private void updateCount(){
        int count=selected.size();
        preview.setText("预览 ("+count+"/"+limit+")");
        preview.setEnabled(count>0);
        skin(preview,NEUTRAL,count>0?INK:MUTED,12);
        done.setText(count==0?"添加":"添加 "+count+" 张");
        done.setEnabled(count>0);
        skin(done,count>0?CLAY:NEUTRAL,count>0?Color.WHITE:MUTED,12);
        adapter.notifyDataSetChanged();
    }
    private void selectCell(int position){
        if(inAlbumList()) {
            Album album=new ArrayList<>(albums.values()).get(position);
            albumKey=album.key;
            updateView();
            grid.setSelection(0);
            return;
        }
        if(cameraTile()&&position==0){takePhoto();return;}
        Uri uri=visiblePhotos().get(position-(cameraTile()?1:0)).uri;
        if(!selected.remove(uri)){
            if(selected.size()>=limit){notice("最多可选 "+limit+" 张");return;}
            selected.add(uri);
        }
        updateCount();
    }
    private void notice(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}

    private void showPreview(List<Uri> uris,int index){
        if(uris.isEmpty())return;
        if(previewPanel!=null)root.removeView(previewPanel);
        tabs.setVisibility(View.GONE);
        message.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        heading.setText("已选预览  "+(index+1)+"/"+uris.size());
        back.setText("返回");
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(NEUTRAL);
        image.setContentDescription("已选照片 "+(index+1));
        panel.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView strip=new HorizontalScrollView(this);
        LinearLayout stripRow=rowBar();
        stripRow.setPadding(dp(8),dp(8),dp(8),dp(8));
        for(int i=0;i<uris.size();i++){
            final int target=i;
            PhotoCell thumb=new PhotoCell();
            thumb.bind(new Photo(uris.get(i),"已选照片 "+(i+1)),true,"",i==index);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(56),dp(56));
            params.rightMargin=dp(5);
            stripRow.addView(thumb,params);
            thumb.setOnClickListener(v->showPreview(uris,target));
        }
        strip.addView(stripRow);
        panel.addView(strip);
        root.addView(panel,root.indexOfChild(grid)+1,new LinearLayout.LayoutParams(-1,0,1));
        previewPanel=panel;
        image.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){swipeStart=event.getX();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                float delta=event.getX()-swipeStart;
                if(Math.abs(delta)>dp(46)){
                    int next=index+(delta<0?1:-1);
                    if(next>=0&&next<uris.size())showPreview(uris,next);
                }else v.performClick();
            }
            return true;
        });
        if(previewLoad!=null)previewLoad.cancel(false);
        Uri uri=uris.get(index);
        image.setImageBitmap(cache.get(uri));
        previewLoad=thumbnails.submit(()->{
            try {
                Bitmap bitmap=loadThumbnail(uri,Math.min(dp(800),1600));
                runOnUiThread(()->{if(!isDestroyed()&&previewPanel==panel)image.setImageBitmap(bitmap);});
            } catch(IOException | RuntimeException ignored){}
        });
        preview.setText("继续选择");
        preview.setOnClickListener(v->closePreview());
    }
    private void closePreview(){
        if(previewPanel==null)return;
        if(previewLoad!=null)previewLoad.cancel(false);
        root.removeView(previewPanel);
        previewPanel=null;
        grid.setVisibility(View.VISIBLE);
        preview.setOnClickListener(v->showPreview(new ArrayList<>(selected),0));
        updateView();
    }
    @Override public void onBackPressed(){
        if(previewPanel!=null){closePreview();return;}
        if(albumsTab&&!albumKey.isEmpty()){openTab(true);return;}
        finish();
    }

    private String imagePermission(){
        return Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    private boolean fullAccess(){return checkSelfPermission(imagePermission())==PackageManager.PERMISSION_GRANTED;}
    private boolean hasAccess(){
        return fullAccess()||Build.VERSION.SDK_INT>=34
            &&checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;
    }
    private Bitmap loadThumbnail(Uri uri,int size) throws IOException {
        if(!PictureProvider.isCapture(uri))return getContentResolver().loadThumbnail(uri,new Size(size,size),null);
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(PictureProvider.captureFile(this,uri)),
            (decoder,info,source)->{
                Size dimensions=info.getSize();
                int width=dimensions.getWidth(),height=dimensions.getHeight();
                float scale=Math.min(1f,(float)size/Math.max(width,height));
                decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));
            });
    }
    private void askAccess(){
        String[] permissions=Build.VERSION.SDK_INT>=34
            ?new String[]{imagePermission(),Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
            :new String[]{imagePermission()};
        requestPermissions(permissions,PERMISSION);
    }
    private void accessAction(){
        if(hasAccess()||shouldShowRequestPermissionRationale(imagePermission()))askAccess();
        else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null)));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==PERMISSION)load();
    }

    private void takePhoto(){
        if(selected.size()>=limit){notice("最多可选 "+limit+" 张");return;}
        if(cameraUri!=null)return;
        try {
            cameraUri=PictureProvider.createCapture(this);
            Intent capture=new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT,cameraUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            capture.setClipData(ClipData.newRawUri("拍照",cameraUri));
            startActivityForResult(capture,CAMERA);
        } catch(IOException | RuntimeException e){
            discardCamera();
            notice("无法打开相机");
        }
    }
    private void discardCamera(){
        Uri uri=cameraUri;
        cameraUri=null;
        if(PictureProvider.isCapture(uri))PictureProvider.captureFile(this,uri).delete();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=CAMERA||cameraUri==null)return;
        if(result!=RESULT_OK){discardCamera();return;}
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(PictureProvider.captureFile(this,cameraUri).getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0){
            discardCamera();
            notice("未能读取拍摄的照片");
            return;
        }
        captures.add(0,new Photo(cameraUri,"刚拍的照片"));
        selected.add(cameraUri);
        cameraUri=null;
        load();
    }
    private void returnSelection(){
        if(selected.isEmpty())return;
        ArrayList<Uri> uris=new ArrayList<>(selected);
        ClipData clip=ClipData.newUri(getContentResolver(),"参考图",uris.get(0));
        for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
        Intent result=new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        result.setClipData(clip);
        finishedSelection=true;
        setResult(RESULT_OK,result);
        finish();
    }

    private void load(){
        int revision=++loadRevision;
        if(!loaded)updateView();
        catalog.execute(()->{
            Map<String,Album> result=new LinkedHashMap<>();
            List<Photo> images=new ArrayList<>();
            Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            String[] columns={MediaStore.Images.Media._ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,MediaStore.Images.Media.VOLUME_NAME};
            boolean failed=false;
            try(Cursor cursor=getContentResolver().query(collection,columns,null,null,
                MediaStore.Images.Media.DATE_ADDED+" DESC, "+MediaStore.Images.Media._ID+" DESC")){
                while(cursor!=null&&cursor.moveToNext()&&!Thread.currentThread().isInterrupted()){
                    String volume=cursor.getString(4);
                    Uri media=volume==null?collection:MediaStore.Images.Media.getContentUri(volume);
                    Photo photo=new Photo(ContentUris.withAppendedId(media,cursor.getLong(0)),cursor.getString(2));
                    images.add(photo);
                    String name=cursor.getString(1);
                    if(name!=null&&!name.isEmpty()){
                        String key=volume+"/"+cursor.getString(3);
                        result.computeIfAbsent(key,k->new Album(k,name)).photos.add(photo);
                    }
                }
            } catch(RuntimeException e){failed=true;}
            final boolean error=failed;
            runOnUiThread(()->{
                if(stopped||isDestroyed()||revision!=loadRevision)return;
                loaded=true;queryFailed=error;
                allPhotos.clear();allPhotos.addAll(captures);allPhotos.addAll(images);
                albums.clear();albums.putAll(result);
                if(!albumKey.isEmpty()&&!albums.containsKey(albumKey))albumKey="";
                if(!error){
                    Set<Uri> available=new LinkedHashSet<>();
                    for(Photo photo:allPhotos)available.add(photo.uri);
                    selected.retainAll(available);
                }
                if(previewPanel==null)updateView();
            });
        });
    }

    private int dp(float size){return Math.round(size*getResources().getDisplayMetrics().density);}
    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount(){
            if(inAlbumList())return albums.size();
            return visiblePhotos().size()+(cameraTile()?1:0);
        }
        @Override public Object getItem(int position){
            if(inAlbumList())return new ArrayList<>(albums.values()).get(position);
            return cameraTile()&&position==0?null:visiblePhotos().get(position-(cameraTile()?1:0));
        }
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            PhotoCell cell=recycled instanceof PhotoCell?(PhotoCell)recycled:new PhotoCell();
            Object item=getItem(position);
            if(item instanceof Album){
                Album album=(Album)item;
                cell.bind(album.photos.get(0),true,album.name+" · "+album.photos.size(),false);
            }else if(item instanceof Photo)cell.bind((Photo)item,false,"",false);
            else cell.bindCamera();
            return cell;
        }
    }
    private class PhotoCell extends LinearLayout {
        final FrameLayout cover;
        final ImageView image;
        final TextView check,caption;
        Uri bound;
        Future<?> pending;

        PhotoCell(){
            super(AlbumActivity.this);
            setOrientation(LinearLayout.VERTICAL);
            cover=new FrameLayout(AlbumActivity.this){
                @Override protected void onMeasure(int width,int height){
                    super.onMeasure(width,MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width),MeasureSpec.EXACTLY));
                }
            };
            image=new ImageView(AlbumActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.addView(image,new FrameLayout.LayoutParams(-1,-1));
            check=label("",12,Color.WHITE);
            check.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP|Gravity.END);
            marker.setMargins(dp(4),dp(4),dp(4),dp(4));
            cover.addView(check,marker);
            addView(cover,new LinearLayout.LayoutParams(-1,-2));
            caption=label("",12,MUTED);
            caption.setSingleLine();
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(0,dp(6),0,0);
            addView(caption);
        }
        void bindCamera(){
            if(pending!=null)pending.cancel(false);
            bound=null;
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setImageResource(android.R.drawable.ic_menu_camera);
            image.setColorFilter(CLAY);
            cover.setBackground(shape(PALE,8));
            cover.setClipToOutline(true);
            caption.setVisibility(View.GONE);
            check.setVisibility(View.VISIBLE);
            check.setText("拍照");
            check.setTextColor(CLAY);
            check.setTextSize(10);
            check.setBackground(null);
            check.getLayoutParams().width=dp(32);
            setContentDescription("拍照添加参考图");
        }
        void bind(Photo photo,boolean isCover,String title,boolean current){
            boolean chosen=selected.contains(photo.uri);
            setContentDescription(title.isEmpty()?(photo.label==null?"照片":photo.label)+(chosen?"，已选":""):title);
            cover.setBackground(shape(current?CLAY:NEUTRAL,isCover?9:3));
            cover.setClipToOutline(true);
            caption.setText(title);
            caption.setVisibility(title.isEmpty()?View.GONE:View.VISIBLE);
            check.setVisibility(isCover?View.GONE:View.VISIBLE);
            check.setText(chosen?String.valueOf(new ArrayList<>(selected).indexOf(photo.uri)+1):"");
            check.setTextColor(Color.WHITE);
            check.setTextSize(12);
            check.getLayoutParams().width=dp(23);
            GradientDrawable badge=shape(chosen?CLAY:0x44000000,30);
            badge.setStroke(dp(1),Color.WHITE);
            check.setBackground(badge);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.clearColorFilter();
            if(photo.uri.equals(bound))return;
            bound=photo.uri;
            if(pending!=null)pending.cancel(false);
            Bitmap cached=cache.get(photo.uri);
            image.setImageBitmap(cached);
            if(cached!=null)return;
            pending=thumbnails.submit(()->{
                try {
                    Bitmap preview=loadThumbnail(photo.uri,dp(145));
                    cache.put(photo.uri,preview);
                    runOnUiThread(()->{if(!isDestroyed()&&photo.uri.equals(bound))image.setImageBitmap(preview);});
                }catch(IOException | RuntimeException ignored){}
            });
        }
    }
}
