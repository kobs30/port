package ai.portscan;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.*;
import androidx.camera.core.Camera;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.mlkit.vision.common.InputImage;
import ai.portscan.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MainActivity extends ComponentActivity {
    private static final int BG = Color.rgb(16,23,19), PANEL = Color.rgb(27,37,30);
    private static final int LIME = Color.rgb(200,245,107), INK = Color.rgb(239,244,234);
    private static final int MUTED = Color.rgb(155,171,157), AMBER = Color.rgb(255,202,120);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ContainerAssembler assembler = new ContainerAssembler();
    private final OcrEngine ocr = new OcrEngine();
    private ScanHistory history;
    private PreviewView preview;
    private TextView status, partial, fragments, metrics, resultCount, instruction, cameraHint;
    private LinearLayout resultList;
    private Button scanButton, videoButton, burstButton, newButton, importButton, torchButton;
    private Button confirmedFilterButton, allFilterButton;
    private TextView filterHint;
    private boolean confirmedOnly;
    private String currentNumbers = "";
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageAnalysis analysis;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean binding, videoMode, videoHasAnalysis, startingVideo, finalizingVideo, burst, torch;
    private volatile boolean live = true, importing, cancelled, destroyed, foreground;
    private volatile int generation;
    private long lastAnalyzed, lastResultTime, lastErrorTime;
    private int burstCount, analyzedCount;
    private int burstGeneration;
    private double averageMs, measuredFps;
    private String sweep = UUID.randomUUID().toString(), activeSource = "Camera";
    private File recordingFile;
    private String exportText;

    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) bindCamera(false, null);
            else {
                cameraHint.setText("Camera permission is required\nTap to open settings");
                status.setText("You can still import photos or video");
            }
        });
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
        new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (!uris.isEmpty()) {
                for (Uri uri : uris) try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
                newSweep();
                processMedia(uris);
            }
        });
    private final ActivityResultLauncher<String> export = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            if (uri == null) return;
            String contents = exportText != null ? exportText : history.csv(confirmedOnly);
            worker.execute(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null) throw new IOException("Could not open file");
                    stream.write(contents.getBytes(StandardCharsets.UTF_8));
                    main.post(() -> toast("CSV saved"));
                } catch (Exception e) { showError("Could not save CSV", e); }
            });
        });

    @Override protected void attachBaseContext(Context base) {
        Configuration english = new Configuration(base.getResources().getConfiguration());
        english.setLocales(new LocaleList(Locale.ENGLISH));
        english.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(english));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new ScanHistory(this);
        confirmedOnly = getSharedPreferences("settings", 0).getBoolean("confirmedOnly", true);
        buildUi();
        updateFilter();
        renderHistory();
        if (hasCameraPermission()) bindCamera(false, null);
        else permission.launch(Manifest.permission.CAMERA);
    }

    private void buildUi() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout top = row(); top.setPadding(dp(20), dp(8), dp(12), dp(8));
        TextView brand = text("▣  PORTSCAN", 18, INK); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1)); brand.setGravity(Gravity.CENTER_VERTICAL);
        Button files = button("Captures", false); files.setOnClickListener(v -> showCaptures()); top.addView(files);
        Button save = button("CSV ↗", false); save.setOnClickListener(v -> {
            if (history.visibleEntries(confirmedOnly).isEmpty()) {
                toast(confirmedOnly ? "No verified numbers yet. Select All to view other results." : "No complete numbers to export yet");
                return;
            }
            exportText = history.csv(confirmedOnly); export.launch("portscan-" + System.currentTimeMillis() + ".csv");
        }); top.addView(save); root.addView(top);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(dp(20), dp(6), dp(20), dp(20));
        TextView title = text("Container in focus.", 29, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(title);
        TextView subtitle = text("One container · one scan", 14, MUTED); padVertical(subtitle, 6); body.addView(subtitle);

        LinearLayout filters = row();
        confirmedFilterButton = button("Verified", false);
        confirmedFilterButton.setOnClickListener(v -> setConfirmedOnly(true)); addEqual(filters, confirmedFilterButton);
        allFilterButton = button("All", false);
        allFilterButton.setOnClickListener(v -> setConfirmedOnly(false)); addEqual(filters, allFilterButton);
        body.addView(filters);
        filterHint = text("", 12, MUTED); padVertical(filterHint, 8); body.addView(filterHint);

        FrameLayout viewfinder = new FrameLayout(this); viewfinder.setBackground(round(Color.BLACK, 18));
        viewfinder.setClipToOutline(true);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        viewfinder.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        viewfinder.addView(new GuideView(), new FrameLayout.LayoutParams(-1, -1));
        cameraHint = text("Connecting camera…", 15, INK); cameraHint.setGravity(Gravity.CENTER);
        cameraHint.setBackgroundColor(0xAA101713);
        viewfinder.addView(cameraHint, new FrameLayout.LayoutParams(-1, -1));
        cameraHint.setOnClickListener(v -> {
            if (hasCameraPermission()) bindCamera(false, null);
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        });
        body.addView(viewfinder, new LinearLayout.LayoutParams(-1, dp(250)));
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint point = preview.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()); v.performClick();
            }
            return true;
        });
        metrics = text("ON DEVICE   ·   WAITING FOR A FRAME", 11, LIME); padVertical(metrics, 10); body.addView(metrics);
        LinearLayout captures = row();
        videoButton = button("● Video", false); videoButton.setOnClickListener(v -> toggleVideo()); addEqual(captures, videoButton);
        burstButton = button("▤ Burst", false); burstButton.setOnClickListener(v -> toggleBurst()); addEqual(captures, burstButton);
        torchButton = button("Light", false); torchButton.setOnClickListener(v -> {
            if (camera == null || !camera.getCameraInfo().hasFlashUnit()) { toast("Flash is not available"); return; }
            torch = !torch; camera.getCameraControl().enableTorch(torch); torchButton.setText(torch ? "Light: on" : "Light");
        }); addEqual(captures, torchButton); body.addView(captures);

        LinearLayout current = column(); current.setPadding(dp(16), dp(14), dp(16), dp(14)); current.setBackground(round(PANEL, 16));
        TextView label = text("CURRENT SCAN", 11, MUTED); current.addView(label);
        partial = text("···· ······ ·", 26, LIME); partial.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); padVertical(partial, 8); current.addView(partial);
        status = text("Point the camera at the start of the number", 14, INK); current.addView(status);
        fragments = text("Fragments will appear here", 12, MUTED); padVertical(fragments, 7); current.addView(fragments);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.topMargin = dp(16); body.addView(current, cardParams);
        instruction = text("Move slowly along the number. Keep at least 3 characters from the previous view visible. Tap the preview to focus.", 13, MUTED);
        padVertical(instruction, 12); body.addView(instruction);

        LinearLayout actions = row();
        newButton = button("New scan", false); newButton.setOnClickListener(v -> newSweep()); addEqual(actions, newButton);
        importButton = button("Import +", false); importButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("One container per import")
                .setMessage("Select video or photos of one container. They will be combined in a new scan. Video is sampled every 200 ms.")
                .setPositiveButton("Choose files", (d, w) -> picker.launch(new String[]{"image/*", "video/*"}))
                .setNegativeButton("Cancel", null).show();
        }); addEqual(actions, importButton); body.addView(actions);
        resultCount = text("FOUND NUMBERS · 0", 12, MUTED); padVertical(resultCount, 16); body.addView(resultCount);
        resultList = column(); body.addView(resultList);
        TextView foot = text("A matching check digit does not guarantee an accurate reading. Compare the assembled number with the container.", 12, MUTED);
        padVertical(foot, 16); body.addView(foot);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = column(); bottom.setPadding(dp(20), dp(8), dp(20), dp(12));
        scanButton = button("Ⅱ  Pause scanning", true);
        scanButton.setOnClickListener(v -> {
            if (importing) { cancelled = true; scanButton.setEnabled(false); status.setText("Stopping processing…"); return; }
            live = !live;
            if (live && !hasCameraPermission()) permission.launch(Manifest.permission.CAMERA);
            status.setText(live ? "Scanning resumed" : "Paused · collected fragments are retained");
            updateControls();
        }); bottom.addView(scanButton, new LinearLayout.LayoutParams(-1, dp(52))); root.addView(bottom);
        setContentView(root);
    }

    private void bindCamera(boolean withVideo, Runnable ready) {
        if (!hasCameraPermission() || binding || destroyed) return;
        binding = true; updateControls();
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get(); provider.unbindAll();
                int rotation = preview.getDisplay() == null ? Surface.ROTATION_0 : preview.getDisplay().getRotation();
                Preview cameraPreview = new Preview.Builder().setTargetRotation(rotation).build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                    .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
                analysis = new ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(worker, this::analyze);
                videoMode = withVideo; videoHasAnalysis = true;
                if (withVideo) {
                    Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build();
                    videoCapture = VideoCapture.withOutput(recorder); videoCapture.setTargetRotation(rotation);
                    try {
                        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis, videoCapture);
                    } catch (IllegalArgumentException unsupported) {
                        provider.unbindAll(); videoHasAnalysis = false;
                        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, videoCapture);
                    }
                } else {
                    imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(resolution).setTargetRotation(rotation).build();
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis, imageCapture);
                }
                cameraHint.setVisibility(View.GONE); torch = false; torchButton.setText("Light");
                if (ready != null) ready.run();
            } catch (Exception e) {
                startingVideo = false;
                cameraHint.setVisibility(View.VISIBLE); cameraHint.setText("Camera unavailable\nTap to retry or import files");
                showError("Could not start camera", e);
            } finally { binding = false; updateControls(); }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (!live || importing || !foreground || destroyed || now - lastAnalyzed < 125) return;
            lastAnalyzed = now;
            android.media.Image image = proxy.getImage();
            if (image == null) return;
            int epoch = generation;
            OcrEngine.Result result = ocr.read(InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees()));
            main.post(() -> { if (!importing && foreground && live) receive(result, epoch, "Camera"); });
        } catch (Exception e) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorTime > 5000) { lastErrorTime = now; showError("Could not read frame", e); }
        } finally { proxy.close(); }
    }

    private void receive(OcrEngine.Result result, int epoch, String source) {
        if (destroyed || epoch != generation) return;
        activeSource = source;
        analyzedCount++;
        averageMs = analyzedCount == 1 ? result.elapsedMs : averageMs * .8 + result.elapsedMs * .2;
        long now = SystemClock.elapsedRealtime();
        if (lastResultTime > 0 && now > lastResultTime) measuredFps = measuredFps == 0 ? 1000.0 / (now - lastResultTime)
            : measuredFps * .8 + 200.0 / (now - lastResultTime);
        lastResultTime = now;
        metrics.setText(String.format(Locale.ENGLISH, "OCR %.0f ms  ·  %.1f fps  ·  %d frames", averageMs, measuredFps, analyzedCount));
        ContainerAssembler.Snapshot s = assembler.accept(result.regions);
        boolean changed = false;
        for (ContainerAssembler.Candidate candidate : s.candidates) changed |= history.add(candidate, source, sweep);
        if (changed) { history.save(); renderHistory(); }
        LinkedHashSet<String> otherNumbers = new LinkedHashSet<>();
        for (String region : result.regions) {
            Matcher m = Pattern.compile("[0-9]+(?:[.,][0-9]+)*").matcher(region);
            while (m.find()) otherNumbers.add(m.group());
        }
        currentNumbers = String.join(" · ", otherNumbers);
        renderCurrentResult(s);
    }

    private void setConfirmedOnly(boolean enabled) {
        if (confirmedOnly == enabled) return;
        confirmedOnly = enabled;
        getSharedPreferences("settings", 0).edit().putBoolean("confirmedOnly", enabled).apply();
        updateFilter();
        renderHistory();
        renderCurrentResult(assembler.snapshot());
    }

    private void updateFilter() {
        confirmedFilterButton.setBackground(round(confirmedOnly ? LIME : PANEL, 12));
        confirmedFilterButton.setTextColor(confirmedOnly ? BG : INK);
        confirmedFilterButton.setSelected(confirmedOnly);
        allFilterButton.setBackground(round(confirmedOnly ? PANEL : LIME, 12));
        allFilterButton.setTextColor(confirmedOnly ? INK : BG);
        allFilterButton.setSelected(!confirmedOnly);
        filterHint.setText(confirmedOnly ? "Matching check digit only · also applies to CSV"
            : "All candidates and fragments · CSV: all complete numbers");
        fragments.setVisibility(confirmedOnly ? View.GONE : View.VISIBLE);
    }

    private void renderCurrentResult(ContainerAssembler.Snapshot s) {
        List<ContainerAssembler.Candidate> visible = new ArrayList<>();
        for (ContainerAssembler.Candidate candidate : s.candidates)
            if (!confirmedOnly || candidate.checksumValid) visible.add(candidate);
        String number = confirmedOnly ? (visible.isEmpty() ? "" : visible.get(0).number) : s.partial;
        partial.setText(ContainerCode.format(number + "·".repeat(Math.max(0, 11 - number.length()))));
        if (history.entries.size() >= 1000) status.setText("History is full: export CSV, then clear history");
        else if (s.limited) status.setText("Too many candidates · start a new scan closer to the number");
        else if (s.candidates.size() > 1) status.setText(confirmedOnly
            ? "Multiple candidates · select All to compare" : "Multiple candidates · review the results");
        else if (!visible.isEmpty()) {
            var candidate = visible.get(0);
            status.setText(!candidate.checksumValid ? "Review needed: check digit does not match"
                : (candidate.assembled ? "Assembled from fragments" : "Complete number read")
                    + (candidate.repeated ? " · seen again" : " · awaiting repeat") + " · check digit matches");
        } else if (confirmedOnly) status.setText("Waiting for a complete number with a matching check digit");
        else if (!number.isEmpty()) status.setText("Partial number found · keep moving");
        else status.setText("Looking for the prefix: 3 letters + U, J or Z");
        fragments.setText("Fragments: " + (s.fragments.isEmpty() ? "—" : String.join(" · ", s.fragments))
            + (currentNumbers.isEmpty() ? "" : "\nNumbers in frame: " + currentNumbers));
    }

    private void newSweep() {
        generation++; assembler.clear(); sweep = UUID.randomUUID().toString();
        currentNumbers = "";
        analyzedCount = 0; averageMs = measuredFps = 0; lastResultTime = 0;
        partial.setText("···· ······ ·"); fragments.setText("Fragments of the next container will appear here");
        status.setText("New scan · start with the letter prefix");
        metrics.setText("ON DEVICE   ·   NEW SCAN");
    }

    private void toggleVideo() {
        if (recording != null) {
            finalizingVideo = true; recording.stop(); videoButton.setText("Saving…"); updateControls(); return;
        }
        if (!hasCameraPermission()) { permission.launch(Manifest.permission.CAMERA); return; }
        startingVideo = true; live = true; updateControls();
        bindCamera(true, () -> {
            try {
                recordingFile = captureFile("mp4");
                final File savedFile = recordingFile;
                final boolean needsReplay = !videoHasAnalysis;
                recording = videoCapture.getOutput().prepareRecording(this,
                    new FileOutputOptions.Builder(savedFile).build()).start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Start) {
                        startingVideo = false; videoButton.setText("■ Stop video");
                        instruction.setText(needsReplay ? "Recording video. On this phone, recognition will run after you stop recording."
                            : "Recording and recognizing. Move slowly around one container, keeping characters overlapping between views.");
                        updateControls();
                    } else if (event instanceof VideoRecordEvent.Finalize done) {
                        recording = null; startingVideo = false; finalizingVideo = false; videoButton.setText("● Video");
                        if (destroyed) return;
                        if (done.hasError()) toast("Recording ended with error " + done.getError() + ". Check the file in Captures.");
                        else toast("Video saved to Captures");
                        bindCamera(false, null); updateControls();
                        if (!done.hasError() && needsReplay && !destroyed) processMedia(List.of(Uri.fromFile(savedFile)));
                    }
                });
            } catch (Exception e) {
                startingVideo = false; showError("Could not start recording", e);
                main.post(() -> bindCamera(false, null));
            }
        });
    }

    private void toggleBurst() {
        if (burst) { burst = false; burstGeneration++; updateControls(); return; }
        if (!hasCameraPermission()) { permission.launch(Manifest.permission.CAMERA); return; }
        if (imageCapture == null || videoMode) { bindCamera(false, this::toggleBurst); return; }
        burst = true; burstGeneration++; burstCount = 0; live = true; updateControls(); captureNext();
    }

    private void captureNext() {
        if (!burst || destroyed || !foreground) return;
        final int epoch = generation;
        final int burstToken = burstGeneration;
        final File file;
        try { file = captureFile("jpg"); }
        catch (IOException e) { burst = false; updateControls(); showError("Could not save photo", e); return; }
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            new ImageCapture.OnImageSavedCallback() {
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    if (destroyed) return;
                    if (burstToken == burstGeneration) {
                        burstCount++; if (burst) burstButton.setText("■ Burst " + burstCount);
                    }
                    worker.execute(() -> {
                        Bitmap bitmap = null;
                        try {
                            bitmap = MediaReader.photo(MainActivity.this, Uri.fromFile(file), 1600);
                            OcrEngine.Result result = ocr.read(bitmap);
                            main.post(() -> receive(result, epoch, file.getName()));
                        } catch (Exception e) { showError("Could not read photo", e); }
                        finally {
                            if (bitmap != null) bitmap.recycle();
                            main.postDelayed(() -> {
                                if (destroyed || burstToken != burstGeneration) return;
                                if (burstCount >= 60) { burst = false; toast("60 photos saved. Start another burst if needed."); }
                                updateControls(); captureNext();
                            }, 700);
                        }
                    });
                }
                @Override public void onError(@NonNull ImageCaptureException e) {
                    if (destroyed || burstToken != burstGeneration) return;
                    burst = false; updateControls(); showError("Photo capture failed", e);
                }
            });
    }

    private void processMedia(List<Uri> uris) {
        if (importing) return;
        importing = true; cancelled = false; final int epoch = generation;
        instruction.setText("Processing one container. Stopping will keep results already found.");
        updateControls();
        worker.execute(() -> {
            int failed = 0;
            for (int i = 0; i < uris.size() && !cancelled && !destroyed; i++) {
                Uri uri = uris.get(i);
                try {
                    String type = getContentResolver().getType(uri);
                    boolean video = type != null ? type.startsWith("video/") : uri.toString().toLowerCase(Locale.ROOT).endsWith(".mp4");
                    if (video) readVideo(uri, epoch, i + 1, uris.size());
                    else {
                        Bitmap bitmap = MediaReader.photo(this, uri, 1600);
                        try {
                            OcrEngine.Result result = ocr.read(bitmap);
                            int index = i + 1;
                            main.post(() -> {
                                receive(result, epoch, "Photo " + index);
                                instruction.setText("Photo " + index + " of " + uris.size());
                            });
                        } finally { bitmap.recycle(); }
                    }
                } catch (Exception e) { failed++; showError("Could not process file " + (i + 1), e); }
            }
            int failedFiles = failed;
            main.post(() -> {
                if (destroyed) return;
                importing = false; live = false; scanButton.setEnabled(true);
                instruction.setText((cancelled ? "Processing stopped." : "Processing complete.")
                    + (failedFiles > 0 ? " Failed files: " + failedFiles + "." : "")
                    + " Start a new scan before capturing a different container.");
                updateControls();
            });
        });
    }

    private void readVideo(Uri uri, int epoch, int fileIndex, int fileCount) throws Exception {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(this, uri);
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationText == null) throw new IOException("Could not read video duration");
            long duration = Long.parseLong(durationText);
            if (duration <= 0) throw new IOException("The video is empty");
            int decoded = 0;
            for (long t = 0; t < duration && !cancelled && !destroyed; t += 200) {
                Bitmap bitmap = MediaReader.videoFrame(retriever, t * 1000, 1280);
                if (bitmap == null) continue;
                try {
                    OcrEngine.Result result = ocr.read(bitmap); decoded++;
                    long timestamp = t;
                    main.post(() -> {
                        receive(result, epoch, String.format(Locale.ROOT, "Video %d · %.1f s", fileIndex, timestamp / 1000.0));
                        instruction.setText(String.format(Locale.ENGLISH, "Video %d/%d · %.1f / %.1f s · 200 ms intervals",
                            fileIndex, fileCount, timestamp / 1000.0, duration / 1000.0));
                    });
                } finally { bitmap.recycle(); }
            }
            if (decoded == 0 && !cancelled) throw new IOException("Could not decode video frames");
        }
    }

    private void renderHistory() {
        List<ScanHistory.Entry> entries = history.visibleEntries(confirmedOnly);
        Collections.reverse(entries);
        resultCount.setText((confirmedOnly ? "VERIFIED · " : "ALL NUMBERS · ") + entries.size()
            + (confirmedOnly ? " / " + history.entries.size() : ""));
        resultList.removeAllViews();
        if (history.entries.isEmpty()) {
            resultList.addView(text("Complete numbers will appear here. Repeated readings within one scan are combined.", 14, MUTED)); return;
        }
        if (entries.isEmpty()) resultList.addView(text("No numbers with a matching check digit yet. Select All to see other candidates.", 14, MUTED));
        for (int i = 0; i < Math.min(entries.size(), 20); i++) {
            ScanHistory.Entry entry = entries.get(i);
            LinearLayout card = column(); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(round(PANEL, 12));
            TextView number = text(ContainerCode.format(entry.number), 22, entry.checksum ? INK : AMBER);
            number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); card.addView(number);
            TextView detail = text((entry.checksum ? "Check digit ✓" : "Check digit mismatch")
                + (entry.assembled ? " · assembled" : " · complete") + (entry.repeated ? " · seen again" : " · seen once"), 11, entry.checksum ? LIME : AMBER);
            padVertical(detail, 5); card.addView(detail);
            card.setOnClickListener(v -> {
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Container number", entry.number)); toast("Number copied");
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8); resultList.addView(card, p);
        }
        if (entries.size() > 20) {
            Button all = button("Show full list (" + entries.size() + ")", false);
            all.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(confirmedOnly ? "Verified numbers" : "All numbers")
                .setItems(entries.stream().map(e -> ContainerCode.format(e.number) + (e.checksum ? " · check digit ✓" : " · review")).toArray(String[]::new),
                    (d, which) -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Number", entries.get(which).number)))
                .setPositiveButton("Close", null).show()); resultList.addView(all);
        }
        Button clear = button("Clear history", false);
        clear.setOnClickListener(v -> {
            if (busy()) { toast("Stop recording or processing first"); return; }
            new AlertDialog.Builder(this).setTitle("Clear all recorded numbers?")
            .setMessage("Export a CSV first if needed. All numbers, including hidden results, will be removed. Photos and videos will remain in Captures.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear all", (d, w) -> {
                history.entries.clear(); history.save(); newSweep(); renderHistory();
            }).show();
        });
        clear.setEnabled(!busy()); resultList.addView(clear);
    }

    private boolean busy() { return recording != null || burst || importing || startingVideo || finalizingVideo; }
    private void updateControls() {
        if (videoButton == null) return;
        boolean busy = busy();
        newButton.setEnabled(!busy && !binding); importButton.setEnabled(!busy && !binding);
        videoButton.setEnabled(!importing && !burst && !binding && !startingVideo && !finalizingVideo);
        burstButton.setEnabled(!importing && recording == null && !binding && !startingVideo && !finalizingVideo);
        if (!burst) burstButton.setText("▤ Burst");
        scanButton.setEnabled(!startingVideo && !finalizingVideo);
        scanButton.setText(importing ? "■  Stop processing" : live ? "Ⅱ  Pause scanning" : "▶  Resume scanning");
        getWindow().setFlags((live || busy) ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private File captureFile(String extension) throws IOException {
        File folder = new File(getFilesDir(), "captures");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create the capture folder");
        return new File(folder, "portscan-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6) + "." + extension);
    }

    private void showCaptures() {
        File[] files = new File(getFilesDir(), "captures").listFiles((dir, name) -> name.endsWith(".mp4") || name.endsWith(".jpg"));
        if (files == null || files.length == 0) { toast("Record a video or take a photo burst first"); return; }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        String[] names = Arrays.stream(files).map(f -> f.getName() + " · " + String.format(Locale.ENGLISH, "%.1f MB", f.length() / 1048576.0)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Captures on this device").setItems(names, (d, index) -> {
            File file = files[index];
            String type = file.getName().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            new AlertDialog.Builder(this).setTitle(file.getName()).setItems(new String[]{"Open", "Share", "Scan again"}, (dialog, action) -> {
                if (action == 2) {
                    if (busy()) { toast("Stop the current recording or processing first"); return; }
                    newSweep(); processMedia(List.of(uri)); return;
                }
                Intent intent = action == 0 ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    : new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("PortScan capture", uri));
                try { startActivity(Intent.createChooser(intent, "PortScan capture")); }
                catch (ActivityNotFoundException e) { toast("No app is available to open this file"); }
            }).show();
        }).setPositiveButton("Close", null).show();
    }

    @Override protected void onStart() { super.onStart(); foreground = true; }
    @Override protected void onResume() { super.onResume(); updateControls(); }
    @Override protected void onStop() {
        foreground = false; burst = false; burstGeneration++;
        if (recording != null) { finalizingVideo = true; recording.stop(); }
        if (history != null) history.save();
        updateControls(); super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; cancelled = true; generation++;
        if (provider != null) provider.unbindAll();
        worker.execute(ocr::close); worker.shutdown(); super.onDestroy();
    }
    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        if (preview.getDisplay() != null) {
            int rotation = preview.getDisplay().getRotation();
            if (analysis != null) analysis.setTargetRotation(rotation);
            if (imageCapture != null) imageCapture.setTargetRotation(rotation);
        }
    }
    private boolean hasCameraPermission() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void showError(String label, Exception e) {
        android.util.Log.e("PortScan", label, e);
        main.post(() -> { if (!destroyed) toast(label); });
    }
    private void toast(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); return t; }
    private void padVertical(View view, int padding) { view.setPadding(0, dp(padding), 0, dp(padding)); }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button button(String title, boolean primary) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(12); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(46)); b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setTextColor(primary ? BG : INK); b.setBackgroundTintList(null); b.setBackground(round(primary ? LIME : PANEL, 12));
        b.setStateListAnimator(null); return b;
    }
    private void addEqual(LinearLayout row, View child) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.setMarginEnd(dp(5)); row.addView(child, p); }
    private final class GuideView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        GuideView() { super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); paint.setColor(LIME); paint.setStrokeWidth(dp(2)); paint.setStyle(Paint.Style.STROKE);
            float l = dp(22), r = getWidth() - l, t = getHeight() * .31f, b = getHeight() * .69f, arm = dp(19);
            for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++) {
                float x = xi == 0 ? l : r, y = yi == 0 ? t : b;
                canvas.drawLine(x, y, x + (x == l ? arm : -arm), y, paint);
                canvas.drawLine(x, y, x, y + (y == t ? arm : -arm), paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(11)); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            canvas.drawText("MOVE ALONG THE NUMBER →", l, getHeight() - dp(20), paint);
        }
    }
}
