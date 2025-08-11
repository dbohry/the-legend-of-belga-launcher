package com.lhamacorp.games.tlob;

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

    // --- Config (game) ---
    private static final String REPO = "dbohry/the-legend-of-belga";
    private static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "TLOB-Launcher/1.0 (+https://github.com/" + REPO + ")";
    private static final Path HOME_DIR = Path.of(System.getProperty("user.home"), ".tlob");
    private static final Path INSTALLED_PROPS = HOME_DIR.resolve("installed.properties");
    private static final Path GAME_JAR = HOME_DIR.resolve("game.jar");

    // --- Self-update (launcher) ---
    private static final String LAUNCHER_REPO = "dbohry/the-legend-of-belga-launcher";
    private static final String API_LAUNCHER_LATEST =
        "https://api.github.com/repos/" + LAUNCHER_REPO + "/releases/latest";
    private static final Path LAUNCHER_JAR = HOME_DIR.resolve("launcher.jar");

    // --- UI ---
    private final JLabel status = new JLabel("Checking for updates…");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JButton btnPlay = new JButton("Play");
    private final JButton btnUpdate = new JButton("Update Game");
    private final JButton btnUpdateLauncher = new JButton("Update Launcher");
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

    private String launcherLocalVersion = "0.0.0";
    private String launcherLatestVersion = null;
    private String launcherLatestJarUrl = null;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().setVisible(true));
    }

    public Launcher() {
        super("The Legend of Belga — Launcher");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(500, 240);
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
        btnUpdateLauncher.setEnabled(false);

        btnQuit.addActionListener(e -> System.exit(0));
        btnPlay.addActionListener(e -> launchGame());
        btnUpdate.addActionListener(e -> startUpdateGame());
        btnUpdateLauncher.addActionListener(e -> startUpdateLauncher());

        btns.add(btnPlay);
        btns.add(btnUpdate);
        btns.add(btnUpdateLauncher);
        btns.add(btnQuit);

        add(panel, BorderLayout.CENTER);
        add(btns, BorderLayout.SOUTH);

        // Center on screen
        setLocationRelativeTo(null);

        // Ensure folders exist
        try { Files.createDirectories(HOME_DIR); } catch (IOException ignored) {}

        loadLocalVersion();
        loadLauncherLocalVersion();

        checkLatestGameRelease();
        checkLatestLauncherRelease();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { exec.shutdown(); }
        });
    }

    // ------------------------ Game update flow ------------------------

    private void loadLocalVersion() {
        if (Files.exists(INSTALLED_PROPS)) {
            try (var in = Files.newInputStream(INSTALLED_PROPS)) {
                var p = new Properties();
                p.load(in);
                localVersion = p.getProperty("version", localVersion);
            } catch (IOException ignored) {}
        }
        status.setText("Installed game: " + localVersion + " — checking for updates…");
    }

    private void saveLocalGameVersion(String version) {
        try (var out = Files.newOutputStream(INSTALLED_PROPS)) {
            var p = new Properties();
            p.setProperty("version", version);
            p.setProperty("path", GAME_JAR.toString());
            p.store(out, "TLOB installed version");
        } catch (IOException ignored) {}
    }

    private void checkLatestGameRelease() {
        btnPlay.setEnabled(Files.exists(GAME_JAR));
        bar.setIndeterminate(true);

        exec.submit(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API_LATEST))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET();

                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) {
                    b.header("Authorization", "Bearer " + token.trim());
                }

                var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("GitHub API " + resp.statusCode());

                String json = resp.body();
                latestVersion = extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                latestJarUrl  = extractFirstJarUrl(json);

                SwingUtilities.invokeLater(() -> {
                    if (latestVersion == null || latestJarUrl == null) {
                        status.setText("Could not find latest game .jar asset.");
                        bar.setIndeterminate(false);
                        bar.setValue(0);
                        btnUpdate.setEnabled(false);
                        btnPlay.setEnabled(Files.exists(GAME_JAR));
                        return;
                    }
                    boolean newer = isNewer(latestVersion, localVersion) || !Files.exists(GAME_JAR);
                    btnUpdate.setEnabled(newer);
                    btnPlay.setEnabled(Files.exists(GAME_JAR));
                    bar.setIndeterminate(false);
                    bar.setValue(newer ? 0 : 100);
                    if (!newer) bar.setString("Game up to date");
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

    private void startUpdateGame() {
        btnUpdate.setEnabled(false);
        btnPlay.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setString(null);
        status.setText("Downloading game " + latestVersion + "…");

        exec.submit(() -> {
            Path tmp = null;
            try {
                if (latestJarUrl == null) throw new IOException("No game asset URL.");
                tmp = Files.createTempFile("tlob-", ".jar.part");

                long contentLen = contentLength(latestJarUrl);
                SwingUtilities.invokeLater(() -> {
                    if (contentLen > 0) { bar.setIndeterminate(false); bar.setValue(0); }
                });

                var req = HttpRequest.newBuilder(URI.create(latestJarUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();

                Path finalTmp = tmp;
                http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body().transferTo(new OutputStream() {
                    long read = 0;
                    final OutputStream out = Files.newOutputStream(finalTmp, StandardOpenOption.TRUNCATE_EXISTING);

                    @Override public void write(int b) throws IOException {
                        out.write(b);
                        if (contentLen > 0 && (++read % 8192 == 0)) updateProgress(read, contentLen);
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        if (contentLen > 0) { read += len; updateProgress(read, contentLen); }
                    }
                    @Override public void close() throws IOException { out.close(); }
                });

                Files.createDirectories(HOME_DIR);
                Files.move(tmp, GAME_JAR, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                saveLocalGameVersion(latestVersion);

                SwingUtilities.invokeLater(() -> {
                    status.setText("Installed game " + latestVersion + ". Ready to play.");
                    bar.setValue(100);
                    bar.setString("Done");
                    btnPlay.setEnabled(true);
                });

            } catch (Exception ex) {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                SwingUtilities.invokeLater(() -> {
                    status.setText("Game download failed: " + ex.getMessage());
                    btnUpdate.setEnabled(true);
                    btnPlay.setEnabled(Files.exists(GAME_JAR));
                    bar.setIndeterminate(false);
                    bar.setValue(0);
                });
            }
        });
    }

    // ------------------------ Launcher self-update flow ------------------------

    private void loadLauncherLocalVersion() {
        String v = getClass().getPackage() != null
            ? getClass().getPackage().getImplementationVersion()
            : null;
        if (v != null && !v.isBlank()) launcherLocalVersion = v.trim();
    }

    private void checkLatestLauncherRelease() {
        exec.submit(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API_LAUNCHER_LATEST))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET();

                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) {
                    b.header("Authorization", "Bearer " + token.trim());
                }

                var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) throw new IOException("GitHub API " + resp.statusCode());

                String json = resp.body();
                launcherLatestVersion = extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                launcherLatestJarUrl  = extractFirstJarUrl(json);

                SwingUtilities.invokeLater(() -> {
                    boolean newer = launcherLatestVersion != null
                        && launcherLatestJarUrl != null
                        && isNewer(launcherLatestVersion, launcherLocalVersion);
                    btnUpdateLauncher.setEnabled(newer);
                    if (!newer && bar.isIndeterminate()) {
                        bar.setIndeterminate(false);
                        bar.setValue(100);
                        bar.setString("Up to date");
                    }
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> btnUpdateLauncher.setEnabled(false));
            }
        });
    }

    private void startUpdateLauncher() {
        btnUpdateLauncher.setEnabled(false);
        bar.setIndeterminate(true);
        bar.setString(null);
        status.setText("Downloading launcher " + launcherLatestVersion + "…");

        exec.submit(() -> {
            Path tmp = null;
            try {
                if (launcherLatestJarUrl == null) throw new IOException("No launcher asset URL.");
                tmp = Files.createTempFile("tlob-launcher-", ".jar.part");

                long contentLen = contentLength(launcherLatestJarUrl);
                SwingUtilities.invokeLater(() -> {
                    if (contentLen > 0) { bar.setIndeterminate(false); bar.setValue(0); }
                });

                HttpRequest req = HttpRequest.newBuilder(URI.create(launcherLatestJarUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();

                Path finalTmp = tmp;
                http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body().transferTo(new OutputStream() {
                    long read = 0;
                    final OutputStream out = Files.newOutputStream(finalTmp, StandardOpenOption.TRUNCATE_EXISTING);
                    @Override public void write(int b) throws IOException {
                        out.write(b);
                        if (contentLen > 0 && (++read % 8192 == 0)) updateProgress(read, contentLen);
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        if (contentLen > 0) { read += len; updateProgress(read, contentLen); }
                    }
                    @Override public void close() throws IOException { out.close(); }
                });

                Files.createDirectories(HOME_DIR);
                Path self = getSelfJarPath();

                if (self != null && Files.isRegularFile(self) && Files.isSameFile(self, LAUNCHER_JAR)) {
                    // Running from ~/.tlob/launcher.jar → stage and replace via script
                    Path staged = HOME_DIR.resolve("launcher.jar.new");
                    Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                    SwingUtilities.invokeLater(() -> {
                        status.setText("Updating launcher… restarting.");
                        bar.setIndeterminate(true);
                    });

                    writeAndRunSelfReplaceScript(staged, LAUNCHER_JAR, findJava());

                    // Exit cleanly on the EDT (no interrupting the worker before dispose)
                    SwingUtilities.invokeLater(() -> {
                        exec.shutdown();
                        setVisible(false);
                        dispose();
                        System.exit(0);
                    });
                    return;
                }

                // Otherwise, install to ~/.tlob/launcher.jar and start it
                Files.move(tmp, LAUNCHER_JAR, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                SwingUtilities.invokeLater(() -> {
                    status.setText("Launcher installed. Restarting updated launcher…");
                    bar.setValue(100);
                    bar.setString("Done");
                });

                new ProcessBuilder(findJava(), "-jar", LAUNCHER_JAR.toString())
                    .directory(HOME_DIR.toFile())
                    .inheritIO()
                    .start();

                // Exit cleanly on the EDT
                SwingUtilities.invokeLater(() -> {
                    exec.shutdown();
                    setVisible(false);
                    dispose();
                    System.exit(0);
                });

            } catch (Exception ex) {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                SwingUtilities.invokeLater(() -> {
                    status.setText("Launcher update failed: " + ex.getMessage());
                    btnUpdateLauncher.setEnabled(true);
                    if (bar.isIndeterminate()) { bar.setIndeterminate(false); bar.setValue(0); }
                });
            }
        });
    }

    private static Path getSelfJarPath() {
        try {
            var url = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return null;
            var uri = url.toURI();
            Path p = Path.of(uri);
            return (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAndRunSelfReplaceScript(Path staged, Path dest, String javaPath) throws IOException {
        if (isWindows()) {
            Path bat = Files.createTempFile("tlob-replace-", ".bat");
            String script = """
                @echo off
                set SRC=%1
                set DEST=%2
                set JAVA=%3
                :loop
                del /f /q "%DEST%" >nul 2>&1
                if exist "%DEST%" (
                  timeout /t 1 >nul
                  goto loop
                )
                move /y "%SRC%" "%DEST%"
                start "" "%JAVA%" -jar "%DEST%"
                del "%~f0"
                """;
            Files.writeString(bat, script);
            new ProcessBuilder("cmd", "/c", "start", "", bat.toString(),
                staged.toString(), dest.toString(), javaPath)
                .inheritIO()
                .start();
        } else {
            Path sh = Files.createTempFile("tlob-replace-", ".sh");
            String script = """
                #!/bin/sh
                SRC="$1"
                DEST="$2"
                JAVA="$3"
                i=0
                while [ $i -lt 30 ]; do
                  if rm -f "$DEST" 2>/dev/null; then break; fi
                  i=$((i+1))
                  sleep 1
                done
                mv -f "$SRC" "$DEST"
                nohup "$JAVA" -jar "$DEST" >/dev/null 2>&1 &
                rm -- "$0"
                """;
            Files.writeString(sh, script);
            sh.toFile().setExecutable(true);
            new ProcessBuilder(sh.toString(), staged.toString(), dest.toString(), javaPath)
                .inheritIO()
                .start();
        }
    }

    // ------------------------ Common helpers ------------------------

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

            // Exit cleanly from the EDT
            SwingUtilities.invokeLater(() -> {
                exec.shutdown();
                setVisible(false);
                dispose();
                System.exit(0);
            });

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

    private static boolean isNewer(String remote, String local) {
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
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    private static String extract(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractFirstJarUrl(String json) {
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
        } catch (Exception ignored) {}
        return -1;
    }
}
