package studio.yingxu.frame;

import android.Manifest;
import android.app.Application;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w420dp-h800dp-mdpi")
public class AlbumGestureTest {
    private static final int PHOTO_COUNT=400;
    private final Application app=RuntimeEnvironment.getApplication();
    private ActivityController<AlbumActivity> activity;
    private ViewGroup overlay,card;
    private PinchGrid grid;
    private long down;

    @Before public void open() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        ShadowContentResolver.registerProviderInternal("media",new ContentProvider(){
            public boolean onCreate(){return true;}
            public Cursor query(Uri uri,String[] projection,String filter,String[] args,String order){
                MatrixCursor rows=new MatrixCursor(projection);
                for(int i=1;i<=PHOTO_COUNT;i++){
                    rows.addRow(new Object[]{i,"Album","Photo"+i,1,"external_primary",
                        1024L*(i%5)+i,i==PHOTO_COUNT?null:10000L+i});
                }
                return rows;
            }
            public String getType(Uri uri){return "image/jpeg";}
            public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
            public int delete(Uri u,String f,String[] args){throw new UnsupportedOperationException();}
            public int update(Uri u,ContentValues v,String f,String[] args){throw new UnsupportedOperationException();}
        });
        launch();
    }
    private void launch() throws Exception {
        activity=Robolectric.buildActivity(AlbumActivity.class,new Intent(app,AlbumActivity.class)
            .putExtra(AlbumActivity.LIMIT,9).putExtra(AlbumActivity.DRAFT,"[]")).setup();
        overlay=(ViewGroup)((ViewGroup)activity.get().findViewById(android.R.id.content)).getChildAt(0);
        card=(ViewGroup)overlay.getChildAt(1);
        grid=find(card,PinchGrid.class);
        for(int i=0;i<100 && grid.getAdapter().getCount()<PHOTO_COUNT+1;i++){
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        settle();
        assertEquals(PHOTO_COUNT+1,grid.getAdapter().getCount());
    }
    @After public void stop(){if(activity!=null)activity.close();}
    private static <T> T find(View view,Class<T> type){
        if(type.isInstance(view))return type.cast(view);
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                T found=find(group.getChildAt(i),type);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private void layout(){
        overlay.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        overlay.layout(0,0,420,800);
    }
    private void settle(){
        shadowOf(Looper.getMainLooper()).idleFor(350,TimeUnit.MILLISECONDS);layout();
    }
    private void touch(View v,int action,float x,float y){
        long time=SystemClock.uptimeMillis();
        if(action==MotionEvent.ACTION_DOWN)down=time;
        MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);
        v.dispatchTouchEvent(event);event.recycle();
    }
    @Test public void cardTracksDragAndRestoresDraftSelection() throws Exception {
        assertEquals(600,card.getHeight());
        touch(overlay,0,150,400);touch(overlay,2,150,335);layout();
        assertTrue(card.getHeight()>600 && card.getHeight()<800);
        touch(overlay,1,150,335);settle();
        int expanded=card.getHeight();
        assertTrue(expanded>600);
        float handle=card.getY()+10;
        touch(overlay,0,150,handle);touch(overlay,2,150,handle+100);touch(overlay,1,150,handle+100);settle();
        assertEquals(600,card.getHeight());

        View cell=grid.getChildAt(1);
        ((ViewGroup)((ViewGroup)cell).getChildAt(0)).getChildAt(1).performClick();
        handle=card.getY()+10;
        touch(overlay,0,150,handle);touch(overlay,2,150,handle+210);touch(overlay,1,150,handle+210);settle();
        assertTrue(activity.get().isFinishing());
        activity.close();activity=null;
        launch();
        java.util.ArrayList<View> matches=new java.util.ArrayList<>();
        card.findViewsWithText(matches,"添加 1 张",View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1,matches.size());
    }
    private void fingers(int action,float span){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){
            properties[i]=new MotionEvent.PointerProperties();
            properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();
            coords[i].x=180+(i==0?-span:span)*.5f;
            coords[i].y=95;coords[i].pressure=1;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,2,properties,coords,
            0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        grid.dispatchTouchEvent(event);event.recycle();
    }
    @Test public void pinchingInterpolatesAndSettlesBetweenThreeAndSix(){
        grid.setSelection(32);settle();
        int anchor=grid.pointToPosition(180,95);
        assertTrue(anchor>15);
        touch(grid,MotionEvent.ACTION_DOWN,120,95);
        fingers(MotionEvent.ACTION_POINTER_DOWN|(1<<8),120);
        fingers(MotionEvent.ACTION_MOVE,144);
        float shown=ReflectionHelpers.getField(grid,"columns");
        assertEquals(3.333f,shown,.01f);
        assertEquals(3,(int)ReflectionHelpers.getField(grid,"previewColumns"));
        shadowOf(Looper.getMainLooper()).idleFor(110,TimeUnit.MILLISECONDS);
        assertEquals(1f,(float)ReflectionHelpers.getField(grid,"incomingOpacity"),.01f);
        fingers(MotionEvent.ACTION_MOVE,220);
        fingers(MotionEvent.ACTION_POINTER_UP|(1<<8),220);
        touch(grid,MotionEvent.ACTION_UP,70,95);settle();
        assertEquals(3,grid.getNumColumns());
        View anchored=grid.getChildAt(anchor-grid.getFirstVisiblePosition());
        assertNotNull(anchored);
        assertTrue(anchored.getTop()<=95 && anchored.getBottom()>=95);
        assertTrue(grid.getFirstVisiblePosition()>0);
        int secondAnchor=grid.pointToPosition(180,95);
        touch(grid,MotionEvent.ACTION_DOWN,80,95);
        fingers(MotionEvent.ACTION_POINTER_DOWN|(1<<8),200);
        fingers(MotionEvent.ACTION_MOVE,80);
        fingers(MotionEvent.ACTION_POINTER_UP|(1<<8),80);
        touch(grid,MotionEvent.ACTION_UP,140,95);settle();
        assertEquals(6,grid.getNumColumns());
        assertTrue(grid.getFirstVisiblePosition()>0);
        View afterPinch=grid.getChildAt(secondAnchor-grid.getFirstVisiblePosition());
        assertNotNull(afterPinch);
        assertTrue("Anchor "+secondAnchor+" at "+afterPinch.getTop()+".."+afterPinch.getBottom()+
            ", visible start "+grid.getFirstVisiblePosition()+", height "+grid.getHeight(),
            afterPinch.getTop()<=95 && afterPinch.getBottom()>=95);
    }
    @Test public void pinchingKeepsTheSamePhotoInTouchModeDeepInTheGrid(){
        Object attachInfo=ReflectionHelpers.getField(grid,"mAttachInfo");
        ReflectionHelpers.setField(attachInfo,"mInTouchMode",false);
        grid.setSelection(248);
        settle();
        ReflectionHelpers.setField(attachInfo,"mInTouchMode",true);
        grid.onTouchModeChanged(true);
        assertTrue(grid.isInTouchMode());
        assertEquals(AdapterView.INVALID_POSITION,grid.getSelectedItemPosition());
        for(int target:new int[]{6,3,6}){
            int anchor=grid.pointToPosition(180,95);
            assertTrue(anchor>200);
            View before=grid.getChildAt(anchor-grid.getFirstVisiblePosition());
            float fraction=(95-before.getTop())/(float)before.getHeight();
            float start=target==6?200:100, end=target==6?100:225;
            touch(grid,MotionEvent.ACTION_DOWN,180-start/2,95);
            fingers(MotionEvent.ACTION_POINTER_DOWN|(1<<8),start);
            fingers(MotionEvent.ACTION_MOVE,end);
            fingers(MotionEvent.ACTION_POINTER_UP|(1<<8),end);
            touch(grid,MotionEvent.ACTION_UP,180-end/2,95);
            settle();
            assertEquals(target,grid.getNumColumns());
            assertTrue(grid.getFirstVisiblePosition()>150);
            View after=grid.getChildAt(anchor-grid.getFirstVisiblePosition());
            assertNotNull(after);
            assertEquals(95,after.getTop()+fraction*after.getHeight(),3);
        }
    }
    @Test public void sortRowsToggleDirectionAndIncludeModificationTime(){
        assertEquals("Photo1",grid.getChildAt(1).getContentDescription());
        LinearLayout rows=ReflectionHelpers.callInstanceMethod(activity.get(),"buildSortOptions");
        assertEquals(4,rows.getChildCount());
        String[] fields={"添加时间","修改时间","文件名","文件大小"};
        for(int i=0;i<fields.length;i++)assertEquals(fields[i],((Button)rows.getChildAt(i)).getText());
        Button added=(Button)rows.getChildAt(0);
        assertTrue(added.isSelected());
        assertNotNull(added.getCompoundDrawablesRelative()[2]);
        assertSort(rows,0,"Photo400","添加时间，升序");
        assertSort(rows,0,"Photo1","添加时间，降序");
        assertSort(rows,1,"Photo399","修改时间，降序");
        assertSort(rows,1,"Photo1","修改时间，升序");
        assertSort(rows,2,"Photo1","文件名，升序");
        assertSort(rows,2,"Photo99","文件名，降序");
        assertSort(rows,3,"Photo399","文件大小，降序");
        assertSort(rows,3,"Photo5","文件大小，升序");
    }
    private void assertSort(LinearLayout rows,int field,String firstPhoto,String description){
        Button row=(Button)rows.getChildAt(field);
        row.performClick();settle();
        assertTrue(row.isSelected());
        assertEquals(description,row.getContentDescription());
        assertNotNull(row.getCompoundDrawablesRelative()[2]);
        assertEquals("拍照添加参考图",grid.getChildAt(0).getContentDescription());
        assertEquals(firstPhoto,grid.getChildAt(1).getContentDescription());
    }
    @Test public void neighborsMoveBeforeTheFingerIsReleased(){
        grid.getChildAt(1).performClick();
        layout();
        ViewGroup pager;
        java.util.ArrayList<View> matches=new java.util.ArrayList<>();
        card.findViewsWithText(matches,"照片预览，支持双击或双指缩放",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        pager=(ViewGroup)matches.get(0);
        View current=pager.getChildAt(pager.getChildCount()-1);
        View neighbor=pager.getChildAt(0);
        touch(pager,0,260,100);touch(pager,2,160,100);
        assertEquals(-100f,current.getTranslationX(),1f);
        assertEquals(pager.getWidth()-100,neighbor.getTranslationX(),1f);
        touch(pager,1,160,100);settle();
        matches.clear();
        card.findViewsWithText(matches,"照片预览  2/"+PHOTO_COUNT,View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1,matches.size());
    }
}
