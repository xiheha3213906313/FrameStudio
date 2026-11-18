package studio.yingxu.frame;

import org.json.*;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import static org.junit.Assert.*;

public class ArkContractTest {
    private static final Queue<Fixture> replies=new ArrayDeque<>();

    /** Only the JVM test process uses this handler. Production keeps platform HTTPS and trust. */
    @BeforeClass public static void useFixtures() {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL target) {
                Fixture f=replies.remove(); f.target=target; return f;
            }
        });
    }
    @Before public void reset(){replies.clear();}
    private Fixture enqueue(int status,String body)throws Exception {
        Fixture fixture=new Fixture(status,body);replies.add(fixture);return fixture;
    }
    private JSONObject body(String family,int count)throws Exception {
        return new JSONObject().put("refs",new JSONArray()).put("count",count).put("model","example-model")
            .put("prompt","山峰").put("size","2048x2048").put("level","2K").put("rate",new JSONObject().put("family",family));
    }
    @Test public void onlyVisibleImageModelsPassThrough()throws Exception {
        Fixture f=enqueue(200,"{\"data\":[{\"id\":\"text\",\"domain\":\"VLM\"},{\"id\":\"a\",\"domain\":\"ImageGeneration\"},{\"id\":\"b\",\"modalities\":{\"output_modalities\":[\"image\"]},\"status\":\"Retiring\"},{\"id\":\"c\",\"task_type\":[\"ImageGeneration\"]}]}");
        JSONArray models=Ark.models("local-test");
        assertEquals(3,models.length());assertEquals("Retiring",models.getJSONObject(1).getString("status"));
        assertEquals("/api/v3/models",f.target.getPath());assertEquals("Bearer local-test",f.getRequestProperty("Authorization"));
        enqueue(200,"{\"data\":[]}");assertEquals(0,Ark.models("example").length());
    }
    @Test public void groupsUseDocumentedFields()throws Exception {
        Fixture f=enqueue(200,"{\"data\":[],\"usage\":{\"generated_images\":2}}");
        Ark.generate(null,body("lite",4),"local-test");
        JSONObject req=new JSONObject(f.output.toString("UTF-8"));
        assertEquals("/api/v3/images/generations",f.target.getPath());assertEquals("POST",f.getRequestMethod());
        assertEquals("auto",req.getString("sequential_image_generation"));
        assertEquals(4,req.getJSONObject("sequential_image_generation_options").getInt("max_images"));
        assertFalse(req.has("n"));assertEquals("url",req.getString("response_format"));assertFalse(req.getBoolean("watermark"));
    }
    @Test public void singleImageAvoidsUnsupportedGroupFields()throws Exception {
        Fixture f=enqueue(200,"{\"data\":[]}");
        Ark.generate(null,body("55",1).put("transparent",true),"local-test");
        JSONObject req=new JSONObject(f.output.toString("UTF-8"));
        assertEquals("transparent",req.getString("background"));assertEquals("png",req.getString("output_format"));
        assertFalse(req.has("sequential_image_generation"));
    }
    @Test public void explicitErrorsRetainCodeTraceAndRetryDelay()throws Exception {
        for(int code:new int[]{400,401,403,404,429,503}) {
            Fixture f=enqueue(code,"{\"error\":{\"code\":\"Example\",\"message\":\"detail\"}}");
            f.headers.put("X-Request-ID","fixture-id");f.headers.put("Retry-After","30");
            Ark.Rejected err=assertThrows(Ark.Rejected.class,()->Ark.models("local-test"));
            assertEquals(code,err.status);assertTrue(err.getMessage().contains("Example"));assertTrue(err.getMessage().contains("fixture-id"));assertTrue(err.getMessage().contains("30"));
        }
    }
    @Test public void topLevelJsonErrorInHttp200IsHandled()throws Exception {
        enqueue(200,"{\"error\":{\"code\":\"OutputImageSensitiveContentDetected\",\"message\":\"rejected\"}}");
        Ark.Rejected err=assertThrows(Ark.Rejected.class,()->Ark.generate(null,body("55",1),"example"));
        assertTrue(err.getMessage().contains("内容审核"));assertEquals(400,err.status);
    }
    @Test public void responseTruncationCannotMasqueradeAsSuccess()throws Exception {
        enqueue(200,"{\"data\":");assertThrows(JSONException.class,()->Ark.models("example"));
    }
    @Test public void failureBeforeBodyIsDistinguishedFromLostResponse()throws Exception {
        Fixture before=enqueue(200,"{}");before.before=true;
        assertThrows(Ark.NotSent.class,()->Ark.generate(null,body("55",1),"example"));
        Fixture after=enqueue(200,"{}");after.after=true;
        assertThrows(SocketTimeoutException.class,()->Ark.generate(null,body("55",1),"example"));
        assertTrue(after.output.size()>0);assertTrue(replies.isEmpty());
    }
    @Test public void partialResultKeepsSuccessfulPicturesAndItemErrors()throws Exception {
        JSONArray group=new JSONArray().put(new JSONObject().put("url","https://image.example/a").put("size","1024x1024"))
            .put(new JSONObject().put("error",new JSONObject().put("code","OutputImageSensitiveContentDetected")));
        JSONObject result=Ark.generationResult("job",new JSONObject().put("data",group).put("usage",new JSONObject().put("generated_images",1)).put("trace","trace"));
        assertEquals(1,result.getJSONArray("images").length());
        assertEquals("job-0",result.getJSONArray("images").getJSONObject(0).getString("file"));
        assertTrue(result.getJSONArray("errors").getString(0).contains("第 2 张"));
        assertEquals(1,result.getJSONObject("usage").getInt("generated_images"));
        assertEquals("trace",result.getString("trace"));
    }
    @Test public void imageDownloadDoesNotForwardApiKey()throws Exception {
        Fixture redirect=enqueue(302,"");redirect.headers.put("Location","https://second.example/picture");
        Fixture image=enqueue(200,"image-bytes");
        File file=File.createTempFile("frame-test",".bin");
        try {
            Ark.download("https://first.example/picture",file);
            assertNull(redirect.getRequestProperty("Authorization"));assertNull(image.getRequestProperty("Authorization"));
            assertEquals(11,file.length());assertEquals("second.example",image.target.getHost());
        }finally{file.delete();}
    }
    @Test public void intelligentRatioAndClientIdentifierAreSent()throws Exception {
        Fixture fixture=enqueue(200,"{\"data\":[]}");
        List<String> phases=new ArrayList<>();
        Ark.generate(null,body("55",1).put("size","2K"),"example","client-123",
            result->phases.add(result.optString("phase")));
        assertEquals("2K",new JSONObject(fixture.output.toString("UTF-8")).getString("size"));
        assertEquals("client-123",fixture.getRequestProperty("X-Client-Request-Id"));
        assertEquals(Arrays.asList("connecting","uploading","awaiting","response",""),phases);
    }
    @Test public void interruptedStreamKeepsEachAlreadyReturnedLink()throws Exception {
        String partial=new JSONObject().put("type","image_generation.partial_succeeded").put("image_index",2)
            .put("url","https://image.example/a").put("size","1024×1024").toString();
        JSONObject last=new JSONObject().put("trace","fixture-trace");
        List<String> checkpoints=new ArrayList<>();
        String data="data: "+partial+"\n\ndata: "+partial+"\n\n";
        assertThrows(EOFException.class,()->Ark.streamResult(input(data),"job",last,result->checkpoints.add(result.toString())));
        assertFalse(last.optBoolean("finished"));
        assertEquals(1,last.getJSONArray("images").length());
        assertEquals("1024x1024",last.getJSONArray("images").getJSONObject(0).getString("size"));
        assertTrue(checkpoints.get(0).contains("https://image.example/a"));
        assertEquals("fixture-trace",last.getString("trace"));
    }
    @Test public void completedStreamReturnsFinalUsage()throws Exception {
        JSONObject complete=new JSONObject().put("type","image_generation.completed")
            .put("usage",new JSONObject().put("generated_images",3));
        JSONObject result=Ark.streamResult(input("data: "+complete+"\n\n"),"job",new JSONObject(),null);
        assertEquals(3,result.getJSONObject("usage").getInt("generated_images"));
        assertTrue(result.getBoolean("finished"));
        assertTrue(result.getBoolean("incomplete"));
    }
    @Test public void manualBackupLinksRequireOfficialTosHostAndHttps() {
        assertTrue(Ark.isTosLink("https://bucket.tos-cn-beijing.volces.com/object?X-Tos-Signature=example"));
        assertFalse(Ark.isTosLink("https://bucket.tos-cn-beijing.volces.com.evil.example/object"));
        assertFalse(Ark.isTosLink("https://bucket.tos-cn-beijing.volces.com@evil.example/object"));
        assertFalse(Ark.isTosLink("http://tos-cn-beijing.volces.com/object"));
    }
    private InputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
    private static final class Fixture extends HttpsURLConnection {
        final int status;final byte[] bytes;
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Map<String,String> headers=new HashMap<>();
        URL target;boolean before,after;
        Fixture(int status,String data)throws Exception { super(new URL("https://fixture.invalid/"));this.status=status;bytes=data.getBytes(StandardCharsets.UTF_8); }
        @Override public String getHeaderField(String name){return headers.get(name);}
        @Override public int getResponseCode()throws IOException{if(after)throw new SocketTimeoutException("response lost");return status;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(bytes);}
        @Override public OutputStream getOutputStream()throws IOException{if(before)throw new ConnectException("offline");return output;}
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public String getCipherSuite(){return "fixture";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return null;}
    }
}
