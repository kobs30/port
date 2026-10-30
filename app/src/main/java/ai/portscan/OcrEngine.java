package ai.portscan;

import android.graphics.Bitmap;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/** Called only on a serial worker. Camera YUV buffers are passed directly to ML Kit. */
final class OcrEngine implements AutoCloseable {
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    static final class Result {
        final List<String> regions;
        final long elapsedMs;
        Result(List<String> regions, long elapsedMs) { this.regions = regions; this.elapsedMs = elapsedMs; }
    }

    Result read(InputImage image) throws Exception {
        long start = SystemClock.elapsedRealtime();
        Text text = Tasks.await(recognizer.process(image));
        List<String> regions = new ArrayList<>();
        // Preserve spatial blocks. Joining the entire frame could combine different containers.
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) regions.add(line.getText());
            if (block.getLines().size() > 1) regions.add(block.getText());
        }
        return new Result(regions, SystemClock.elapsedRealtime() - start);
    }

    Result read(Bitmap bitmap) throws Exception { return read(InputImage.fromBitmap(bitmap, 0)); }
    @Override public void close() { recognizer.close(); }
}
