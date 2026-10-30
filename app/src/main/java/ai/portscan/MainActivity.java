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
    private String sweep = UUID.randomUUID().toString(), activeSource = "Камера";
    private File recordingFile;
    private String exportText;

    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) bindCamera(false, null);
            else {
                cameraHint.setText("Нет доступа к камере\nНажмите, чтобы открыть настройки");
                status.setText("Можно импортировать фото или видео");
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
            String contents = exportText != null ? exportText : history.csv();
            worker.execute(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null) throw new IOException("Не удалось открыть файл");
                    stream.write(contents.getBytes(StandardCharsets.UTF_8));
                    main.post(() -> toast("CSV сохранён"));
                } catch (Exception e) { showError("Не удалось сохранить CSV", e); }
            });
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new ScanHistory(this);
        buildUi();
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
        Button files = button("Записи", false); files.setOnClickListener(v -> showCaptures()); top.addView(files);
        Button save = button("CSV ↗", false); save.setOnClickListener(v -> {
            if (history.entries.isEmpty()) { toast("Пока нет полных номеров для экспорта"); return; }
            exportText = history.csv(); export.launch("portscan-" + System.currentTimeMillis() + ".csv");
        }); top.addView(save); root.addView(top);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(dp(20), dp(6), dp(20), dp(20));
        TextView title = text("Контейнер в фокусе.", 29, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(title);
        TextView subtitle = text("Один контейнер · один проход", 14, MUTED); padVertical(subtitle, 6); body.addView(subtitle);

        FrameLayout viewfinder = new FrameLayout(this); viewfinder.setBackground(round(Color.BLACK, 18));
        viewfinder.setClipToOutline(true);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        viewfinder.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        viewfinder.addView(new GuideView(), new FrameLayout.LayoutParams(-1, -1));
        cameraHint = text("Подключение камеры…", 15, INK); cameraHint.setGravity(Gravity.CENTER);
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
        metrics = text("НА УСТРОЙСТВЕ   ·   ОЖИДАНИЕ КАДРА", 11, LIME); padVertical(metrics, 10); body.addView(metrics);
        LinearLayout captures = row();
        videoButton = button("● Видео", false); videoButton.setOnClickListener(v -> toggleVideo()); addEqual(captures, videoButton);
        burstButton = button("▤ Серия", false); burstButton.setOnClickListener(v -> toggleBurst()); addEqual(captures, burstButton);
        torchButton = button("Свет", false); torchButton.setOnClickListener(v -> {
            if (camera == null || !camera.getCameraInfo().hasFlashUnit()) { toast("Вспышка недоступна"); return; }
            torch = !torch; camera.getCameraControl().enableTorch(torch); torchButton.setText(torch ? "Свет: вкл" : "Свет");
        }); addEqual(captures, torchButton); body.addView(captures);

        LinearLayout current = column(); current.setPadding(dp(16), dp(14), dp(16), dp(14)); current.setBackground(round(PANEL, 16));
        TextView label = text("ТЕКУЩИЙ ПРОХОД", 11, MUTED); current.addView(label);
        partial = text("···· ······ ·", 26, LIME); partial.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); padVertical(partial, 8); current.addView(partial);
        status = text("Наведите камеру на начало номера", 14, INK); current.addView(status);
        fragments = text("Фрагменты появятся здесь", 12, MUTED); padVertical(fragments, 7); current.addView(fragments);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.topMargin = dp(16); body.addView(current, cardParams);
        instruction = text("Медленно ведите камеру вдоль номера. Оставляйте в следующем кадре хотя бы 3 символа из предыдущего. Нажмите на изображение для фокусировки.", 13, MUTED);
        padVertical(instruction, 12); body.addView(instruction);

        LinearLayout actions = row();
        newButton = button("Новый проход", false); newButton.setOnClickListener(v -> newSweep()); addEqual(actions, newButton);
        importButton = button("Фото / видео +", false); importButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Материалы одного контейнера")
                .setMessage("Выберите видео или несколько фото одного контейнера. Они будут объединены в новый проход. Видео анализируется с шагом 200 мс.")
                .setPositiveButton("Выбрать", (d, w) -> picker.launch(new String[]{"image/*", "video/*"}))
                .setNegativeButton("Отмена", null).show();
        }); addEqual(actions, importButton); body.addView(actions);
        resultCount = text("НАЙДЕННЫЕ НОМЕРА · 0", 12, MUTED); padVertical(resultCount, 16); body.addView(resultCount);
        resultList = column(); body.addView(resultList);
        TextView foot = text("Совпадение контрольной цифры не гарантирует правильное чтение. Сверяйте собранный номер с контейнером.", 12, MUTED);
        padVertical(foot, 16); body.addView(foot);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = column(); bottom.setPadding(dp(20), dp(8), dp(20), dp(12));
        scanButton = button("Ⅱ  Приостановить сканирование", true);
        scanButton.setOnClickListener(v -> {
            if (importing) { cancelled = true; scanButton.setEnabled(false); status.setText("Останавливаем обработку…"); return; }
            live = !live;
            if (live && !hasCameraPermission()) permission.launch(Manifest.permission.CAMERA);
            status.setText(live ? "Сканирование продолжается" : "Пауза · накопленные фрагменты сохранены");
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
                cameraHint.setVisibility(View.GONE); torch = false; torchButton.setText("Свет");
                if (ready != null) ready.run();
            } catch (Exception e) {
                startingVideo = false;
                cameraHint.setVisibility(View.VISIBLE); cameraHint.setText("Камера недоступна\nНажмите для повтора или импортируйте файлы");
                showError("Не удалось запустить камеру", e);
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
            main.post(() -> { if (!importing && foreground && live) receive(result, epoch, "Камера"); });
        } catch (Exception e) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorTime > 5000) { lastErrorTime = now; showError("Не удалось прочитать кадр", e); }
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
        metrics.setText(String.format(Locale.getDefault(), "OCR %.0f мс  ·  %.1f кадр/с  ·  %d кадров", averageMs, measuredFps, analyzedCount));
        ContainerAssembler.Snapshot s = assembler.accept(result.regions);
        String number = s.partial;
        partial.setText(ContainerCode.format(number + "·".repeat(Math.max(0, 11 - number.length()))));
        boolean changed = false;
        for (ContainerAssembler.Candidate candidate : s.candidates) changed |= history.add(candidate, source, sweep);
        if (changed) { history.save(); renderHistory(); }
        if (history.entries.size() >= 1000) status.setText("История заполнена: экспортируйте CSV и очистите историю");
        else if (s.limited) status.setText("Слишком много вариантов · начните новый проход ближе к номеру");
        else if (s.candidates.size() > 1) status.setText("Несколько вариантов · проверьте номера в списке");
        else if (!s.candidates.isEmpty()) {
            var candidate = s.candidates.get(0);
            status.setText(!candidate.checksumValid ? "Проверить: контрольная цифра не совпала"
                : (candidate.assembled ? "Собран из фрагментов" : "Номер прочитан целиком")
                    + (candidate.repeated ? " · повторён" : " · ждём повтор") + " · контрольная цифра совпала");
        } else if (!number.isEmpty()) status.setText("Часть номера найдена · продолжайте движение");
        else status.setText("Ищем начало номера: 3 буквы + U, J или Z");
        LinkedHashSet<String> otherNumbers = new LinkedHashSet<>();
        for (String region : result.regions) {
            Matcher m = Pattern.compile("[0-9]+(?:[.,][0-9]+)*").matcher(region);
            while (m.find()) otherNumbers.add(m.group());
        }
        fragments.setText("Фрагменты: " + (s.fragments.isEmpty() ? "—" : String.join(" · ", s.fragments))
            + (otherNumbers.isEmpty() ? "" : "\nЧисла в кадре: " + String.join(" · ", otherNumbers)));
    }

    private void newSweep() {
        generation++; assembler.clear(); sweep = UUID.randomUUID().toString();
        analyzedCount = 0; averageMs = measuredFps = 0; lastResultTime = 0;
        partial.setText("···· ······ ·"); fragments.setText("Фрагменты нового контейнера появятся здесь");
        status.setText("Новый проход · начните с буквенного префикса");
        metrics.setText("НА УСТРОЙСТВЕ   ·   НОВЫЙ ПРОХОД");
    }

    private void toggleVideo() {
        if (recording != null) {
            finalizingVideo = true; recording.stop(); videoButton.setText("Сохраняем…"); updateControls(); return;
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
                        startingVideo = false; videoButton.setText("■ Стоп видео");
                        instruction.setText(needsReplay ? "Запись видео. На этом телефоне распознавание выполнится после остановки."
                            : "Идёт запись и распознавание. Медленно обходите один контейнер, сохраняя перекрытие символов.");
                        updateControls();
                    } else if (event instanceof VideoRecordEvent.Finalize done) {
                        recording = null; startingVideo = false; finalizingVideo = false; videoButton.setText("● Видео");
                        if (destroyed) return;
                        if (done.hasError()) toast("Запись завершилась с ошибкой " + done.getError() + ". Проверьте файл в «Записи».");
                        else toast("Видео сохранено в «Записи»");
                        bindCamera(false, null); updateControls();
                        if (!done.hasError() && needsReplay && !destroyed) processMedia(List.of(Uri.fromFile(savedFile)));
                    }
                });
            } catch (Exception e) {
                startingVideo = false; showError("Не удалось начать запись", e);
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
        catch (IOException e) { burst = false; updateControls(); showError("Не удалось сохранить фото", e); return; }
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            new ImageCapture.OnImageSavedCallback() {
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    if (destroyed) return;
                    if (burstToken == burstGeneration) {
                        burstCount++; if (burst) burstButton.setText("■ Серия " + burstCount);
                    }
                    worker.execute(() -> {
                        Bitmap bitmap = null;
                        try {
                            bitmap = MediaReader.photo(MainActivity.this, Uri.fromFile(file), 1600);
                            OcrEngine.Result result = ocr.read(bitmap);
                            main.post(() -> receive(result, epoch, file.getName()));
                        } catch (Exception e) { showError("Не удалось прочитать фото", e); }
                        finally {
                            if (bitmap != null) bitmap.recycle();
                            main.postDelayed(() -> {
                                if (destroyed || burstToken != burstGeneration) return;
                                if (burstCount >= 60) { burst = false; toast("Сохранено 60 фото. При необходимости начните следующую серию."); }
                                updateControls(); captureNext();
                            }, 700);
                        }
                    });
                }
                @Override public void onError(@NonNull ImageCaptureException e) {
                    if (destroyed || burstToken != burstGeneration) return;
                    burst = false; updateControls(); showError("Ошибка съёмки", e);
                }
            });
    }

    private void processMedia(List<Uri> uris) {
        if (importing) return;
        importing = true; cancelled = false; final int epoch = generation;
        instruction.setText("Обрабатываем материалы одного контейнера. Отмена сохраняет уже найденные результаты.");
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
                                receive(result, epoch, "Фото " + index);
                                instruction.setText("Фото " + index + " из " + uris.size());
                            });
                        } finally { bitmap.recycle(); }
                    }
                } catch (Exception e) { failed++; showError("Не удалось обработать файл " + (i + 1), e); }
            }
            int failedFiles = failed;
            main.post(() -> {
                if (destroyed) return;
                importing = false; live = false; scanButton.setEnabled(true);
                instruction.setText((cancelled ? "Обработка остановлена." : "Обработка завершена.")
                    + (failedFiles > 0 ? " Ошибок файлов: " + failedFiles + "." : "")
                    + " Начните новый проход перед съёмкой другого контейнера.");
                updateControls();
            });
        });
    }

    private void readVideo(Uri uri, int epoch, int fileIndex, int fileCount) throws Exception {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(this, uri);
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationText == null) throw new IOException("Не удалось прочитать длительность видео");
            long duration = Long.parseLong(durationText);
            if (duration <= 0) throw new IOException("Видео пустое");
            int decoded = 0;
            for (long t = 0; t < duration && !cancelled && !destroyed; t += 200) {
                Bitmap bitmap = MediaReader.videoFrame(retriever, t * 1000, 1280);
                if (bitmap == null) continue;
                try {
                    OcrEngine.Result result = ocr.read(bitmap); decoded++;
                    long timestamp = t;
                    main.post(() -> {
                        receive(result, epoch, String.format(Locale.ROOT, "Видео %d · %.1f с", fileIndex, timestamp / 1000.0));
                        instruction.setText(String.format(Locale.getDefault(), "Видео %d/%d · %.1f / %.1f с · шаг 200 мс",
                            fileIndex, fileCount, timestamp / 1000.0, duration / 1000.0));
                    });
                } finally { bitmap.recycle(); }
            }
            if (decoded == 0 && !cancelled) throw new IOException("Не удалось декодировать кадры");
        }
    }

    private void renderHistory() {
        resultCount.setText("НАЙДЕННЫЕ НОМЕРА · " + history.entries.size());
        resultList.removeAllViews();
        if (history.entries.isEmpty()) {
            resultList.addView(text("Здесь будут полные номера. Повторы внутри одного прохода объединяются.", 14, MUTED)); return;
        }
        ArrayList<ScanHistory.Entry> entries = new ArrayList<>(history.entries.values()); Collections.reverse(entries);
        for (int i = 0; i < Math.min(entries.size(), 20); i++) {
            ScanHistory.Entry entry = entries.get(i);
            LinearLayout card = column(); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(round(PANEL, 12));
            TextView number = text(ContainerCode.format(entry.number), 22, entry.checksum ? INK : AMBER);
            number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); card.addView(number);
            TextView detail = text((entry.checksum ? "Контрольная цифра ✓" : "Проверить контрольную цифру")
                + (entry.assembled ? " · из фрагментов" : " · целиком") + (entry.repeated ? " · повторён" : " · однократно"), 11, entry.checksum ? LIME : AMBER);
            padVertical(detail, 5); card.addView(detail);
            card.setOnClickListener(v -> {
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Номер контейнера", entry.number)); toast("Номер скопирован");
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8); resultList.addView(card, p);
        }
        if (entries.size() > 20) {
            Button all = button("Все номера (" + entries.size() + ")", false);
            all.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("История номеров")
                .setItems(entries.stream().map(e -> ContainerCode.format(e.number) + (e.checksum ? " · цифра ✓" : " · проверить")).toArray(String[]::new),
                    (d, which) -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Номер", entries.get(which).number)))
                .setPositiveButton("Закрыть", null).show()); resultList.addView(all);
        }
        Button clear = button("Очистить историю", false);
        clear.setOnClickListener(v -> {
            if (busy()) { toast("Сначала остановите запись или обработку"); return; }
            new AlertDialog.Builder(this).setTitle("Очистить найденные номера?")
            .setMessage("Сохраните CSV, если он нужен. Исходные фото и видео останутся в «Записи».")
            .setNegativeButton("Отмена", null).setPositiveButton("Очистить", (d, w) -> {
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
        if (!burst) burstButton.setText("▤ Серия");
        scanButton.setEnabled(!startingVideo && !finalizingVideo);
        scanButton.setText(importing ? "■  Остановить обработку" : live ? "Ⅱ  Приостановить сканирование" : "▶  Продолжить сканирование");
        getWindow().setFlags((live || busy) ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private File captureFile(String extension) throws IOException {
        File folder = new File(getFilesDir(), "captures");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Нет места для записи");
        return new File(folder, "portscan-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6) + "." + extension);
    }

    private void showCaptures() {
        File[] files = new File(getFilesDir(), "captures").listFiles((dir, name) -> name.endsWith(".mp4") || name.endsWith(".jpg"));
        if (files == null || files.length == 0) { toast("Сначала запишите видео или серию фото"); return; }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        String[] names = Arrays.stream(files).map(f -> f.getName() + " · " + String.format(Locale.getDefault(), "%.1f МБ", f.length() / 1048576.0)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Записи на устройстве").setItems(names, (d, index) -> {
            File file = files[index];
            String type = file.getName().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            new AlertDialog.Builder(this).setTitle(file.getName()).setItems(new String[]{"Открыть", "Поделиться", "Распознать заново"}, (dialog, action) -> {
                if (action == 2) {
                    if (busy()) { toast("Сначала остановите текущую запись или обработку"); return; }
                    newSweep(); processMedia(List.of(uri)); return;
                }
                Intent intent = action == 0 ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    : new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Запись PortScan", uri));
                try { startActivity(Intent.createChooser(intent, "Запись PortScan")); }
                catch (ActivityNotFoundException e) { toast("Нет приложения для открытия этого файла"); }
            }).show();
        }).setPositiveButton("Закрыть", null).show();
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
    private void showError(String label, Exception e) { main.post(() -> { if (!destroyed) toast(label + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }); }
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
            canvas.drawText("ДВИГАЙТЕСЬ ВДОЛЬ НОМЕРА →", l, getHeight() - dp(20), paint);
        }
    }
}
