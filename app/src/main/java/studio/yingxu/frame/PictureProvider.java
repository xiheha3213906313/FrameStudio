package studio.yingxu.frame;

import android.content.*;
import android.database.*;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

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
    File image(Uri uri) {
        String name=uri.getLastPathSegment();
        if(!Studio.safe(name)) throw new IllegalArgumentException();
        return new File(new File(getContext().getFilesDir(),"images"),name);
    }
    static String exportName(File f) {return "映序-"+f.getName()+("image/png".equals(mime(f))?".png":".jpg");}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return mime(image(uri));}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
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
