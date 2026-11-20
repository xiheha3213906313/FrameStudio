package studio.yingxu.frame;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import org.json.JSONObject;
import java.io.*;
import java.util.zip.CRC32;

final class MacImages {
    static final long INPUT_BYTES = 24L * 1024 * 1024;
    static final long OUTPUT_BYTES = 272L * 1024 * 1024;
    static final int INPUT_EDGE = 12000;

    static int[] dimensions(File file, JSONObject limits) throws IOException {
        long bytes = Math.min(INPUT_BYTES, limits == null ? INPUT_BYTES : limits.optLong("maxInputBytes", INPUT_BYTES));
        if (!file.isFile() || file.length() == 0 || file.length() > bytes)
            throw new IOException("原图不可用或超过 Mac 每张图片的大小上限（24 MiB）。");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int edge = Math.min(INPUT_EDGE, limits == null ? INPUT_EDGE : limits.optInt("maxInputEdge", INPUT_EDGE));
        long pixels = Math.min(50_000_000L, limits == null ? 50_000_000L : limits.optLong("maxInputPixels", 50_000_000L));
        int w = bounds.outWidth, h = bounds.outHeight;
        if (!("image/png".equals(bounds.outMimeType) || "image/jpeg".equals(bounds.outMimeType) || "image/webp".equals(bounds.outMimeType)))
            throw new IOException("Mac 仅接受 PNG、JPEG 或 WebP 原图，请选择符合格式的图片。");
        if (Math.min(w,h) < 16 || Math.max(w,h) > edge || (long) w * h > pixels)
            throw new IOException("原图超出 Mac 的像素限制。短边至少 16，长边最多 " + edge + " 像素，总像素最多 " + pixels + "。");
        int orientation = orientation(file);
        return orientation >= 5 && orientation <= 8 ? new int[]{h, w} : new int[]{w, h};
    }

    private static int orientation(File file) {
        try { return new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); }
        catch (IOException e) { return 1; }
    }

    static void preview(File source, File destination) throws IOException {
        int[] size = dimensions(source, null);
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (Math.max(size[0], size[1]) / decode.inSampleSize > 1600) decode.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), decode);
        if (bitmap == null) throw new IOException("无法读取原图预览。");
        Matrix transform = new Matrix();
        switch (orientation(source)) {
            case 2: transform.setScale(-1, 1); break;
            case 3: transform.setRotate(180); break;
            case 4: transform.setScale(1, -1); break;
            case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
            case 6: transform.setRotate(90); break;
            case 7: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case 8: transform.setRotate(-90); break;
        }
        File temp = new File(destination.getPath() + ".part");
        Bitmap oriented = bitmap;
        try {
            if (!transform.isIdentity()) oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
            try (OutputStream out = new FileOutputStream(temp)) {
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("无法保存原图预览。");
            }
            if (!temp.renameTo(destination)) throw new IOException("无法保存原图预览。");
        } finally {
            temp.delete();
            if (oriented != bitmap) oriented.recycle();
            bitmap.recycle();
        }
    }

    static void snapshot(InputStream input, File target) throws IOException {
        File temp = new File(target.getPath() + ".part");
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                try { Ark.copy(input, output, INPUT_BYTES); }
                catch (IOException error) {
                    if (temp.length() > INPUT_BYTES - 32768) throw new IOException("原图超过 Mac 每张图片的 24 MiB 上限。");
                    throw error;
                }
                output.getFD().sync();
            }
            dimensions(temp, null);
            if (!temp.renameTo(target)) throw new IOException("无法保存原图快照，请检查手机存储。");
        } finally { temp.delete(); }
    }

    static void verifyPng(File file, int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) throw new IOException("Mac 原图尺寸超出限制。");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readLong() != 0x89504e470d0a1a0aL) throw new IOException("Mac 返回的原图不是 PNG。");
            byte[] buffer = new byte[32768];
            long remaining = file.length() - 8;
            boolean header = false, pixels = false;
            while (remaining >= 12) {
                int size = in.readInt();
                if (size < 0 || size > remaining - 12) break;
                in.readFully(buffer, 0, 4);
                String type = new String(buffer, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                CRC32 crc = new CRC32();
                crc.update(buffer, 0, 4);
                if (!header && (!"IHDR".equals(type) || size != 13)) break;
                for (int left = size; left > 0;) {
                    int count = Math.min(left, buffer.length);
                    in.readFully(buffer, 0, count);
                    crc.update(buffer, 0, count);
                    if (!header) {
                        java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(buffer);
                        if (data.getInt() != w || data.getInt() != h) throw new IOException("Mac 原图的像素与任务记录不一致。");
                        header = true;
                    }
                    left -= count;
                }
                if (Integer.toUnsignedLong(in.readInt()) != crc.getValue()) break;
                remaining -= 12L + size;
                if ("IDAT".equals(type) && size > 0) pixels = true;
                if ("IEND".equals(type) && size == 0 && remaining == 0 && pixels) return;
            }
        }
        throw new IOException("Mac 返回的 PNG 数据不完整或已损坏。");
    }
}
