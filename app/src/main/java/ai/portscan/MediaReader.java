package ai.portscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.InputStream;

/** Bounded bitmap decoding. No entire video or photo collection is held in RAM. */
final class MediaReader {
    private MediaReader() {}

    static Bitmap photo(Context context, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IllegalArgumentException("Файл не является изображением");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 2)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("Не удалось открыть фото");
        try {
            ExifInterface exif;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) { exif = new ExifInterface(stream); }
            Matrix matrix = new Matrix();
            if (exif.isFlipped()) matrix.postScale(-1, 1);
            matrix.postRotate(exif.getRotationDegrees());
            float scale = Math.min(1f, (float) maxSide / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            matrix.postScale(scale, scale);
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (oriented != bitmap) bitmap.recycle();
            return oriented;
        } catch (Exception e) { bitmap.recycle(); throw e; }
    }

    static Bitmap videoFrame(MediaMetadataRetriever retriever, long timeUs, int maxSide) {
        int width = dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        int height = dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        float scale = Math.min(1f, (float) maxSide / Math.max(width, height));
        // API 27+ scales during decode. API 26 uses one full-size frame at a time.
        if (android.os.Build.VERSION.SDK_INT >= 27)
            return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)));
        Bitmap bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
        if (bitmap == null || scale >= 1) return bitmap;
        Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int)(bitmap.getWidth() * scale)),
            Math.max(1, (int)(bitmap.getHeight() * scale)), true);
        if (smaller != bitmap) bitmap.recycle();
        return smaller;
    }

    private static int dimension(MediaMetadataRetriever r, int key) {
        try { return Math.max(1, Integer.parseInt(r.extractMetadata(key))); }
        catch (Exception ignored) { return 1280; }
    }
}
