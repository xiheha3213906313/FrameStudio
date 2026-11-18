package studio.yingxu.frame;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
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
import java.util.function.IntConsumer;

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
    private PickerOverlay overlay;
    private View backdrop;
    private LinearLayout root,tabs,footer;
    private LinearLayout browser;
    private GridView grid;
    private TextView heading,message;
    private Button back,access,photoTab,albumTab,preview,done;
    private PhotoAdapter adapter;
    private boolean albumsTab,stopped,loaded,queryFailed,finishedSelection;
    private String albumKey="";
    private View previewPanel;
    private Uri previewUri;
    private Future<?> previewLoad;
    private Uri cameraUri;
    private int limit,loadRevision;
    private ValueAnimator sheetAnimation;
    private float sheetProgress;
    private boolean closing;

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
        if(sheetAnimation!=null){
            sheetAnimation.removeAllListeners();
            sheetAnimation.cancel();
        }
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
        overridePendingTransition(0,0);
        overlay=new PickerOverlay();
        backdrop=new View(this);
        backdrop.setBackgroundColor(0x751c1f1a);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdrop.setOnClickListener(v->dismissPicker());
        overlay.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));

        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheet=shape(PAPER,0);
        int radius=dp(22);
        sheet.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});
        root.setBackground(sheet);
        root.setClickable(true);
        overlay.addView(root,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        View handle=new View(this);
        handle.setBackground(shape(0xffcacbc1,3));
        LinearLayout.LayoutParams handleLayout=new LinearLayout.LayoutParams(dp(38),dp(4));
        handleLayout.gravity=Gravity.CENTER_HORIZONTAL;
        handleLayout.topMargin=dp(12);
        handleLayout.bottomMargin=dp(7);
        root.addView(handle,handleLayout);

        LinearLayout header=rowBar();
        header.setPadding(dp(14),0,dp(14),dp(4));
        back=control("返回",v->onBackPressed());
        header.addView(back,new LinearLayout.LayoutParams(dp(58),dp(44)));
        heading=label("参考图",20,INK);
        heading.setTypeface(null,Typeface.BOLD);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        access=control("权限",v->{
            if(previewUri==null)accessAction();else toggleSelection(previewUri);
        });
        header.addView(access,new LinearLayout.LayoutParams(dp(58),dp(44)));
        root.addView(header);

        browser=new AlbumBrowser();
        browser.setOrientation(LinearLayout.VERTICAL);
        root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        tabs=rowBar();
        tabs.setPadding(dp(14),dp(4),dp(14),dp(10));
        photoTab=control("全部照片",v->openTab(false));
        albumTab=control("相册",v->openTab(true));
        LinearLayout.LayoutParams photoLayout=new LinearLayout.LayoutParams(0,dp(40),1);
        photoLayout.rightMargin=dp(8);
        tabs.addView(photoTab,photoLayout);
        tabs.addView(albumTab,new LinearLayout.LayoutParams(0,dp(40),1));
        browser.addView(tabs);

        message=label("",14,MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(20),dp(14),dp(20),dp(14));
        browser.addView(message,new LinearLayout.LayoutParams(-1,-2));
        grid=new GridView(this);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        adapter=new PhotoAdapter();
        grid.setAdapter(adapter);
        browser.addView(grid,new LinearLayout.LayoutParams(-1,0,1));

        View divider=new View(this);
        divider.setBackgroundColor(0xffdedcd3);
        root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        footer=rowBar();
        footer.setPadding(dp(14),dp(10),dp(14),dp(10));
        preview=control("预览",v->{
            if(previewPanel==null)showPreview(new ArrayList<>(selected),0);
            else closePreview();
        });
        done=control("添加",v->returnSelection());
        footer.addView(preview,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams doneLayout=new LinearLayout.LayoutParams(0,dp(44),1);
        doneLayout.leftMargin=dp(10);
        footer.addView(done,doneLayout);
        root.addView(footer);
        overlay.setOnApplyWindowInsetsListener((v,insets)->{
            int left,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=safe.left;right=safe.right;bottom=safe.bottom;
            }else{
                left=insets.getStableInsetLeft();
                right=insets.getStableInsetRight();
                bottom=insets.getStableInsetBottom();
            }
            root.setPadding(left,0,right,bottom);
            return insets;
        });
        setContentView(overlay);
        Window window=getWindow();
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);
        }else{
            View decor=window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        overlay.requestApplyInsets();
        overlay.post(()->{if(!closing&&!isDestroyed())animateSheet(1f);});
        updateView();
    }

    private class PickerOverlay extends FrameLayout {
        PickerOverlay(){super(AlbumActivity.this);}
        @Override protected void onMeasure(int width,int height){
            ViewGroup.LayoutParams layout=root.getLayoutParams();
            layout.height=Math.round(MeasureSpec.getSize(height)*.75f);
            super.onMeasure(width,height);
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,left,top,right,bottom);
            applySheetProgress();
        }
    }
    private void applySheetProgress(){
        backdrop.setAlpha(sheetProgress);
        root.setTranslationY(root.getHeight()*(1f-sheetProgress));
    }
    private void animateSheet(float target){
        if(sheetAnimation!=null)sheetAnimation.cancel();
        sheetAnimation=ValueAnimator.ofFloat(sheetProgress,target);
        sheetAnimation.setDuration(200);
        sheetAnimation.setInterpolator(new PathInterpolator(.22f,.61f,.36f,1f));
        sheetAnimation.addUpdateListener(animation->{
            sheetProgress=(float)animation.getAnimatedValue();
            applySheetProgress();
        });
        if(target==0f)sheetAnimation.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){
                finish();
                overridePendingTransition(0,0);
            }
        });
        sheetAnimation.start();
    }
    private void dismissPicker(){
        if(closing)return;
        closing=true;
        animateSheet(0f);
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
    private class AlbumBrowser extends LinearLayout {
        private final int slop=ViewConfiguration.get(AlbumActivity.this).getScaledTouchSlop();
        private float downX,downY;
        private boolean horizontal,vertical;

        AlbumBrowser(){super(AlbumActivity.this);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX();downY=event.getY();
                    horizontal=false;vertical=false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if(event.getPointerCount()==1&&!vertical){
                        float dx=Math.abs(event.getX()-downX),dy=Math.abs(event.getY()-downY);
                        if(dx>slop&&dx>dy*1.3f)horizontal=true;
                        else if(dy>slop)vertical=true;
                    }
                    break;
            }
            return horizontal;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(Math.abs(dx)>dp(36)&&Math.abs(dx)>Math.abs(dy)*1.3f){
                    boolean showAlbums=dx<0;
                    if(showAlbums!=albumsTab){
                        openTab(showAlbums);
                        grid.setTranslationX(dp(22)*(showAlbums?1:-1));
                        grid.animate().translationX(0).setDuration(150).start();
                    }
                }
            }
            return true;
        }
    }
    private void updateView(){
        boolean folder=albumsTab&&!albumKey.isEmpty();
        Album current=albums.get(albumKey);
        back.setText(folder?"返回":"取消");
        heading.setText(current==null?(inAlbumList()?"本机相册":"参考图"):current.name);
        tabs.setVisibility(folder?View.GONE:View.VISIBLE);
        access.setText("权限");
        skin(access,NEUTRAL,INK,12);
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
        boolean viewing=previewUri!=null;
        preview.setText(viewing?"继续选择":"预览 ("+count+"/"+limit+")");
        preview.setEnabled(viewing||count>0);
        skin(preview,NEUTRAL,viewing||count>0?INK:MUTED,12);
        if(viewing){
            boolean chosen=selected.contains(previewUri);
            access.setVisibility(View.VISIBLE);
            access.setText(chosen?"已选":"选择");
            access.setContentDescription(chosen?"取消选中这张照片":"选中这张照片");
            skin(access,chosen?PALE:NEUTRAL,chosen?CLAY:INK,12);
        }else access.setContentDescription(null);
        done.setText(count==0?"添加":"添加 "+count+" 张");
        done.setEnabled(count>0);
        skin(done,count>0?CLAY:NEUTRAL,count>0?Color.WHITE:MUTED,12);
        adapter.notifyDataSetChanged();
    }
    private void openCell(Object item){
        if(item instanceof Album){
            Album album=(Album)item;
            albumKey=album.key;
            updateView();
            grid.setSelection(0);
        }else if(item instanceof Photo)showPreview(Collections.singletonList(((Photo)item).uri),0);
        else takePhoto();
    }
    private void toggleSelection(Uri uri){
        if(!selected.remove(uri)){
            if(selected.size()>=limit){notice("最多可选 "+limit+" 张");return;}
            selected.add(uri);
        }
        updateCount();
    }
    private void notice(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}

    private void showPreview(List<Uri> uris,int index){
        if(uris.isEmpty())return;
        previewUri=uris.get(index);
        if(previewPanel!=null)root.removeView(previewPanel);
        browser.setVisibility(View.GONE);
        heading.setText(uris.size()==1?"照片预览":"照片预览  "+(index+1)+"/"+uris.size());
        back.setText("返回");
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        ZoomPreview image=new ZoomPreview(direction->{
            int next=index+direction;
            if(next>=0&&next<uris.size())showPreview(uris,next);
        });
        image.setContentDescription("照片预览，支持双击或双指缩放");
        panel.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        if(uris.size()>1){
            HorizontalScrollView strip=new HorizontalScrollView(this);
            LinearLayout stripRow=rowBar();
            stripRow.setPadding(dp(8),dp(8),dp(8),dp(8));
            for(int i=0;i<uris.size();i++){
                final int target=i;
                PhotoCell thumb=new PhotoCell();
                thumb.bind(new Photo(uris.get(i),"照片 "+(i+1)),true,"",i==index);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(56),dp(56));
                params.rightMargin=dp(5);
                stripRow.addView(thumb,params);
                thumb.setOnClickListener(v->showPreview(uris,target));
            }
            strip.addView(stripRow);
            panel.addView(strip);
            strip.post(()->strip.smoothScrollTo(dp(61)*index-strip.getWidth()/2+dp(36),0));
        }
        root.addView(panel,root.indexOfChild(browser)+1,new LinearLayout.LayoutParams(-1,0,1));
        previewPanel=panel;
        updateCount();
        if(previewLoad!=null)previewLoad.cancel(false);
        Uri uri=previewUri;
        image.setBitmap(cache.get(uri));
        previewLoad=thumbnails.submit(()->{
            try {
                Bitmap bitmap=decodePreview(uri,Math.min(dp(800),1600));
                runOnUiThread(()->{if(!isDestroyed()&&previewPanel==panel)image.setBitmap(bitmap);});
            } catch(IOException | RuntimeException error){
                runOnUiThread(()->{if(!isDestroyed()&&previewPanel==panel&&cache.get(uri)==null)notice("无法打开这张照片");});
            }
        });
    }
    private void closePreview(){
        if(previewPanel==null)return;
        if(previewLoad!=null)previewLoad.cancel(false);
        root.removeView(previewPanel);
        previewPanel=null;
        previewUri=null;
        browser.setVisibility(View.VISIBLE);
        updateView();
    }
    @Override public void onBackPressed(){
        if(previewPanel!=null){closePreview();return;}
        if(albumsTab&&!albumKey.isEmpty()){openTab(true);return;}
        dismissPicker();
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
        return decodePreview(uri,size);
    }
    private Bitmap decodePreview(Uri uri,int size) throws IOException {
        ImageDecoder.Source source=PictureProvider.isCapture(uri)
            ?ImageDecoder.createSource(PictureProvider.captureFile(this,uri))
            :ImageDecoder.createSource(getContentResolver(),uri);
        return ImageDecoder.decodeBitmap(source,
            (decoder,info,input)->{
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
        if(selected.isEmpty()||closing)return;
        ArrayList<Uri> uris=new ArrayList<>(selected);
        ClipData clip=ClipData.newUri(getContentResolver(),"参考图",uris.get(0));
        for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
        Intent result=new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        result.setClipData(clip);
        finishedSelection=true;
        setResult(RESULT_OK,result);
        dismissPicker();
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
                if(previewPanel==null)updateView();else updateCount();
            });
        });
    }

    private int dp(float size){return Math.round(size*getResources().getDisplayMetrics().density);}
    private class ZoomPreview extends FrameLayout {
        private final ImageView image;
        private final GestureDetector drag;
        private final ScaleGestureDetector pinch;
        private final IntConsumer onSwipe;
        private float zoom=1f, x,y,downX,downY;
        private boolean canSwipe;

        ZoomPreview(IntConsumer onSwipe){
            super(AlbumActivity.this);
            this.onSwipe=onSwipe;
            setBackgroundColor(NEUTRAL);
            setClipChildren(true);
            image=new ImageView(AlbumActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image,new LayoutParams(-1,-1));
            drag=new GestureDetector(AlbumActivity.this,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent event){return true;}
                @Override public boolean onDoubleTap(MotionEvent event){
                    canSwipe=false;
                    scaleTo(zoom>1f?1f:2.5f,event.getX(),event.getY());
                    return true;
                }
                @Override public boolean onScroll(MotionEvent first,MotionEvent event,float dx,float dy){
                    if(zoom>1f&&!pinch.isInProgress()){
                        x-=dx;y-=dy;
                        applyZoom();
                    }
                    return true;
                }
            });
            pinch=new ScaleGestureDetector(AlbumActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector detector){
                    scaleTo(zoom*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());
                    return true;
                }
            });
            pinch.setQuickScaleEnabled(false);
        }
        void setBitmap(Bitmap bitmap){
            image.setImageBitmap(bitmap);
            applyZoom();
        }
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
            super.onSizeChanged(width,height,oldWidth,oldHeight);
            applyZoom();
        }
        private void scaleTo(float target,float focusX,float focusY){
            float next=Math.max(1f,Math.min(5f,target));
            float ratio=next/zoom;
            float pivotX=focusX-getWidth()/2f,pivotY=focusY-getHeight()/2f;
            x=pivotX+(x-pivotX)*ratio;
            y=pivotY+(y-pivotY)*ratio;
            zoom=next;
            applyZoom();
        }
        private void applyZoom(){
            float limitX=0,limitY=0;
            if(image.getDrawable()!=null){
                float width=image.getDrawable().getIntrinsicWidth(),height=image.getDrawable().getIntrinsicHeight();
                float fit=Math.min(getWidth()/width,getHeight()/height);
                limitX=Math.max(0,(width*fit*zoom-getWidth())/2);
                limitY=Math.max(0,(height*fit*zoom-getHeight())/2);
            }
            x=Math.max(-limitX,Math.min(limitX,x));
            y=Math.max(-limitY,Math.min(limitY,y));
            image.setScaleX(zoom);image.setScaleY(zoom);
            image.setTranslationX(x);image.setTranslationY(y);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                downX=event.getX();downY=event.getY();
                canSwipe=zoom==1f;
            }else if(action==MotionEvent.ACTION_POINTER_DOWN)canSwipe=false;
            pinch.onTouchEvent(event);
            drag.onTouchEvent(event);
            if(action==MotionEvent.ACTION_UP&&canSwipe){
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(Math.abs(dx)>dp(46)&&Math.abs(dx)>Math.abs(dy)*1.3f)onSwipe.accept(dx<0?1:-1);
            }
            return true;
        }
    }
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
            cell.setOnClickListener(v->openCell(item));
            return cell;
        }
    }
    private class PhotoCell extends LinearLayout {
        final FrameLayout cover;
        final FrameLayout selectButton;
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
            selectButton=new FrameLayout(AlbumActivity.this);
            selectButton.setOnClickListener(v->{if(bound!=null)toggleSelection(bound);});
            FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP|Gravity.END);
            marker.setMargins(0,dp(5),dp(5),0);
            selectButton.addView(check,marker);
            cover.addView(selectButton,new FrameLayout.LayoutParams(dp(38),dp(38),Gravity.TOP|Gravity.END));
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
            selectButton.setVisibility(View.GONE);
            setContentDescription("拍照添加参考图");
        }
        void bind(Photo photo,boolean isCover,String title,boolean current){
            boolean chosen=selected.contains(photo.uri);
            setContentDescription(title.isEmpty()?(photo.label==null?"照片":photo.label)+(chosen?"，已选":""):title);
            cover.setBackground(shape(current?CLAY:NEUTRAL,isCover?9:3));
            cover.setClipToOutline(true);
            caption.setText(title);
            caption.setVisibility(title.isEmpty()?View.GONE:View.VISIBLE);
            selectButton.setVisibility(isCover?View.GONE:View.VISIBLE);
            selectButton.setContentDescription(chosen?"取消选中照片":"选中照片");
            check.setText(chosen?String.valueOf(new ArrayList<>(selected).indexOf(photo.uri)+1):"");
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
