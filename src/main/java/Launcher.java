import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Launcher extends JFrame {

    // --- Config ---
    private static final String REPO = "dbohry/the-legend-of-belga";
    private static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "TLOB-Launcher/1.0 (+https://github.com/" + REPO + ")";
    private static final Path HOME_DIR = Path.of(System.getProperty("user.home"), ".tlob");
    private static final Path INSTALLED_PROPS = HOME_DIR.resolve("installed.properties");
    private static final Path GAME_JAR = HOME_DIR.resolve("game.jar");

    // --- UI ---
    private final JLabel status = new JLabel("Checking for updates…");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JButton btnPlay = new JButton("Play");
    private final JButton btnUpdate = new JButton("Update");
    private final JButton btnQuit = new JButton("Quit");

    // --- State ---
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LauncherWorker");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private String localVersion = "0.0.0";
    private String latestVersion = null;
    private String latestJarUrl = null;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().setVisible(true));
    }

    public Launcher() {
        super("The Legend of Belga — Launcher");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(460, 220);
        setLayout(new BorderLayout(12, 12));

        var panel = new JPanel(new BorderLayout(8, 8));
        var top = new JPanel(new GridLayout(0, 1));
        top.add(new JLabel("<html><b>The Legend of Belga</b> — Auto Updater</html>"));
        top.add(status);
        panel.add(top, BorderLayout.NORTH);

        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.CENTER);

        var btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPlay.setEnabled(false);
        btnUpdate.setEnabled(false);
        btnQuit.addActionListener(e -> System.exit(0));
        btnPlay.addActionListener(e -> launchGame());
        btnUpdate.addActionListener(e -> startUpdate());
        btns.add(btnPlay);
        btns.add(btnUpdate);
        btns.add(btnQuit);

        add(panel, BorderLayout.CENTER);
        add(btns, BorderLayout.SOUTH);

        setLocationRelativeTo(null);

        // Ensure folders exist
        try {
            Files.createDirectories(HOME_DIR);
        } catch (IOException ignored) {
        }

        loadLocalVersion();
        checkLatestRelease();

        // Also cleanly stop worker on window close (extra safety)
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exec.shutdownNow();
            }
        });

        loadLocalVersion();
        checkLatestRelease();
    }

    private void loadLocalVersion() {
        if (Files.exists(INSTALLED_PROPS)) {
            try (var in = Files.newInputStream(INSTALLED_PROPS)) {
                var p = new Properties();
                p.load(in);
                localVersion = p.getProperty("version", localVersion);
            } catch (IOException ignored) {
            }
        }
        status.setText("Installed version: " + localVersion + " (checking latest…)");
    }

    private void saveLocalVersion(String version) {
        try (var out = Files.newOutputStream(INSTALLED_PROPS)) {
            var p = new Properties();
            p.setProperty("version", version);
            p.setProperty("path", GAME_JAR.toString());
            p.store(out, "TLOB installed version");
        } catch (IOException e) {
        }
    }

    private void checkLatestRelease() {
        btnPlay.setEnabled(Files.exists(GAME_JAR));
        bar.setIndeterminate(true);

        exec.submit(() -> {
            try {
                var req = HttpRequest.newBuilder(URI.create(API_LATEST))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();

                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) {
                    req = HttpRequest.newBuilder(URI.create(API_LATEST))
                        .header("User-Agent", USER_AGENT)
                        .header("Authorization", "Bearer " + token.trim())
                        .timeout(Duration.ofSeconds(20))
                        .GET().build();
                }

                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("GitHub API " + resp.statusCode());

                String json = resp.body();
                latestVersion = extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                latestJarUrl = extractFirstJarUrl(json);

                SwingUtilities.invokeLater(() -> {
                    if (latestVersion == null || latestJarUrl == null) {
                        status.setText("Could not find latest .jar asset.");
                        bar.setIndeterminate(false);
                        bar.setValue(0);
                        btnUpdate.setEnabled(false);
                        btnPlay.setEnabled(Files.exists(GAME_JAR));
                        return;
                    }
                    status.setText("Latest version: " + latestVersion);
                    boolean newer = isNewer(latestVersion, localVersion) || !Files.exists(GAME_JAR);
                    btnUpdate.setEnabled(newer);
                    btnPlay.setEnabled(Files.exists(GAME_JAR));
                    bar.setIndeterminate(false);
                    bar.setValue(newer ? 0 : 100);
                    if (!newer) bar.setString("Up to date");
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("Offline or API error. You can still play offline if installed.");
                    bar.setIndeterminate(false);
                    btnPlay.setEnabled(Files.exists(GAME_JAR));
                    btnUpdate.setEnabled(false);
                });
            }
        });
    }

    private void startUpdate() {
        btnUpdate.setEnabled(false);
        btnPlay.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setString(null);
        status.setText("Downloading " + latestVersion + "…");

        exec.submit(() -> {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("tlob-", ".jar.part");

                long contentLen = contentLength(latestJarUrl);
                SwingUtilities.invokeLater(() -> {
                    if (contentLen > 0) {
                        bar.setIndeterminate(false);
                        bar.setValue(0);
                    }
                });

                // Download with progress
                var req = HttpRequest.newBuilder(URI.create(latestJarUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();

                Path finalTmp = tmp;
                http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body().transferTo(new OutputStream() {
                    long read = 0;
                    final OutputStream out = Files.newOutputStream(finalTmp, StandardOpenOption.TRUNCATE_EXISTING);

                    @Override
                    public void write(int b) throws IOException {
                        out.write(b);
                        if (contentLen > 0 && (++read % 8192 == 0)) updateProgress(read, contentLen);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        if (contentLen > 0) {
                            read += len;
                            updateProgress(read, contentLen);
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        out.close();
                    }
                });

                // Install atomically
                Files.createDirectories(HOME_DIR);
                Files.move(tmp, GAME_JAR, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                saveLocalVersion(latestVersion);

                SwingUtilities.invokeLater(() -> {
                    status.setText("Installed " + latestVersion + ". Ready to play.");
                    bar.setValue(100);
                    bar.setString("Done");
                    btnPlay.setEnabled(true);
                });

            } catch (Exception ex) {
                if (tmp != null) try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
                SwingUtilities.invokeLater(() -> {
                    status.setText("Download failed: " + ex.getMessage());
                    btnUpdate.setEnabled(true);
                    btnPlay.setEnabled(Files.exists(GAME_JAR));
                    bar.setIndeterminate(false);
                    bar.setValue(0);
                });
            }
        });
    }

    private void updateProgress(long read, long total) {
        int pct = (int) Math.max(0, Math.min(100, (read * 100) / Math.max(1, total)));
        SwingUtilities.invokeLater(() -> {
            bar.setValue(pct);
            bar.setString(pct + "%");
        });
    }

    private void launchGame() {
        try {
            if (!Files.exists(GAME_JAR)) {
                JOptionPane.showMessageDialog(this, "Game not installed yet.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            status.setText("Launching game…");
            new ProcessBuilder(findJava(), "-jar", GAME_JAR.toString())
                .directory(HOME_DIR.toFile())
                .inheritIO()
                .start();

            exec.shutdownNow();
            dispose();
            System.exit(0);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to start game: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }


    private static String findJava() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin", (isWindows() ? "java.exe" : "java"));
        return Files.exists(bin) ? bin.toString() : "java";
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    // --- helpers: version & json extraction (minimal, robust enough for GH latest) ---

    private static boolean isNewer(String remote, String local) {
        // strip leading 'v'
        String r = remote.startsWith("v") ? remote.substring(1) : remote;
        String l = local.startsWith("v") ? local.substring(1) : local;

        String[] a = r.split("\\.");
        String[] b = l.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = (i < a.length) ? parseInt(a[i]) : 0;
            int bi = (i < b.length) ? parseInt(b[i]) : 0;
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extract(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractFirstJarUrl(String json) {
        // Find all browser_download_url and pick first that ends with .jar
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String url = m.group(1);
            if (url.toLowerCase().endsWith(".jar")) return url;
        }
        return null;
    }

    private long contentLength(String url) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            String cl = resp.headers().firstValue("Content-Length").orElse(null);
            if (cl != null) return Long.parseLong(cl);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
