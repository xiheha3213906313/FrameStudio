package studio.yingxu.frame;

import android.content.*;
import android.database.*;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

public class PictureProvider extends ContentProvider {
    static String mime(File file) {
        BitmapFactory.Options opt=new BitmapFactory.Options(); opt.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.toString(),opt);
        return opt.outMimeType==null?"image/jpeg":opt.outMimeType;
    }
    static void thumbnail(File src, File dest) throws IOException {
        BitmapFactory.Options opt=new BitmapFactory.Options(); opt.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(src.toString(),opt);
        opt.inSampleSize=1;
        while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>900) opt.inSampleSize*=2;
        opt.inJustDecodeBounds=false; Bitmap bitmap=BitmapFactory.decodeFile(src.toString(),opt);
        if(bitmap==null) throw new IOException("图片预览失败");
        try(OutputStream out=new FileOutputStream(dest)) {
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,84,out)) throw new IOException("缩略图保存失败");
        } finally {bitmap.recycle();}
    }
    private static final String AUTHORITY="studio.yingxu.frame.pictures";
    static boolean isCapture(Uri uri) {
        if(uri==null||!AUTHORITY.equals(uri.getAuthority()))return false;
        List<String> path=uri.getPathSegments();
        return path.size()==2&&"capture".equals(path.get(0))&&path.get(1).matches("[a-f0-9-]{36}[.]jpg");
    }
    static File captureFile(Context context,Uri uri) {
        if(!isCapture(uri))throw new IllegalArgumentException("无效的拍照文件");
        return new File(new File(context.getCacheDir(),"captures"),uri.getLastPathSegment());
    }
    static Uri createCapture(Context context) throws IOException {
        File directory=new File(context.getCacheDir(),"captures");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建拍照目录");
        Uri uri=new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("capture")
            .appendPath(Studio.id()+".jpg").build();
        if(!captureFile(context,uri).createNewFile())throw new IOException("无法创建拍照文件");
        return uri;
    }
    File image(Uri uri) {
        if(isCapture(uri))return captureFile(getContext(),uri);
        String name=uri.getLastPathSegment();
        if(!Studio.safe(name)) throw new IllegalArgumentException();
        return new File(new File(getContext().getFilesDir(),"images"),name);
    }
    static String exportName(File f) {return "映序-"+f.getName()+("image/png".equals(mime(f))?".png":".jpg");}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return mime(image(uri));}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(isCapture(uri)) {
            File file=captureFile(getContext(),uri);
            if(!file.isFile())throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
        }
        if(!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(image(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        File f=image(uri); String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if(OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=exportName(f);
            if(OpenableColumns.SIZE.equals(columns[i])) row[i]=f.length();
        }
        cursor.addRow(row);return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
