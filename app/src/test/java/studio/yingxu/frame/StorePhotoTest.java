package studio.yingxu.frame;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class StorePhotoTest {
    @Test public void deletesOneOutputWithoutChangingTheTaskOrItsUsage() throws Exception {
        try (Store store = new Store(RuntimeEnvironment.getApplication())) {
            JSONObject first = new JSONObject().put("file","first").put("ready",true).put("size","2048x1152");
            JSONObject second = new JSONObject().put("file","second").put("ready",true);
            JSONObject result = new JSONObject().put("images",new JSONArray().put(first).put(second))
                .put("usage",new JSONObject().put("generated_images",2));
            store.add("task",new JSONObject().put("prompt","提示词"),"fetching");
            store.update("task","fetching",result);
            assertFalse(store.deletePhoto("task","first"));
            assertTrue(store.transition("task","fetching","done"));
            assertFalse(store.deletePhoto("another-task","first"));
            assertTrue(store.deletePhoto("task","first"));
            assertTrue(store.deletePhoto("task","first"));

            JSONObject task = store.find("task");
            assertEquals("done",task.getString("state"));
            assertEquals("提示词",task.getJSONObject("body").getString("prompt"));
            JSONObject saved = task.getJSONObject("result");
            assertEquals(2,saved.getJSONObject("usage").getInt("generated_images"));
            JSONArray outputs = saved.getJSONArray("images");
            assertEquals("2048x1152",outputs.getJSONObject(0).getString("size"));
            assertTrue(outputs.getJSONObject(0).getBoolean("deleted"));
            assertFalse(outputs.getJSONObject(1).optBoolean("deleted"));
        }
    }
}
