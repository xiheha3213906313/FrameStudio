package studio.yingxu.frame;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35}, application=Application.class)
public class AlbumLaunchTest {
    @Test public void opensBeforePhotoPermissionIsGranted() {
        checkLaunch(false);
    }

    @Test public void opensWithPhotoPermission() {
        checkLaunch(true);
    }

    private void checkLaunch(boolean granted) {
        Application app=RuntimeEnvironment.getApplication();
        String permission=Build.VERSION.SDK_INT>=33
            ?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE;
        if(granted)shadowOf(app).grantPermissions(permission);
        else shadowOf(app).denyPermissions(permission);
        Intent intent=new Intent(app,AlbumActivity.class).putExtra(AlbumActivity.LIMIT,9);
        try(ActivityController<AlbumActivity> activity=Robolectric.buildActivity(AlbumActivity.class,intent)){
            activity.setup();
            shadowOf(Looper.getMainLooper()).idle();
            ViewGroup content=activity.get().findViewById(android.R.id.content);
            ViewGroup overlay=(ViewGroup)content.getChildAt(0);
            overlay.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
            overlay.layout(0,0,400,800);
            assertEquals(600,overlay.getChildAt(1).getHeight());

            ArrayList<View> controls=new ArrayList<>();
            overlay.findViewsWithText(controls,"全部照片",View.FIND_VIEWS_WITH_TEXT);
            assertEquals(1,controls.size());
            assertTrue(controls.get(0).performClick());
        }
    }
}
