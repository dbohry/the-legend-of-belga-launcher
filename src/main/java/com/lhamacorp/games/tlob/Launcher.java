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
        btnUpdate.addActionListener(e -> startUpdate(true));
        btnUpdateLauncher.addActionListener(e -> startUpdate(false));

        btns.add(btnPlay);
        btns.add(btnUpdate);
        btns.add(btnUpdateLauncher);
        btns.add(btnQuit);

        add(panel, BorderLayout.CENTER);
        add(btns, BorderLayout.SOUTH);
        setLocationRelativeTo(null);

        try {
            Files.createDirectories(HOME_DIR);
        } catch (IOException ignored) {
        }

        loadLocalVersion();
        loadLauncherLocalVersion();

        checkLatest(true);   // game
        checkLatest(false);  // launcher

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exec.shutdown();
            }
        });
    }

    // ------------------------ Game & Launcher flows (merged) ------------------------

    private void checkLatest(boolean game) {
        if (game) {
            btnPlay.setEnabled(Files.exists(GAME_JAR));
            bar.setIndeterminate(true);
        }

        exec.submit(() -> {
            try {
                String api = game ? API_LATEST : API_LAUNCHER_LATEST;
                String json = httpGetString(api);
                String ver = extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                String url = extractFirstJarUrl(json);

                if (game) {
                    latestVersion = ver;
                    latestJarUrl = url;
                } else {
                    launcherLatestVersion = ver;
                    launcherLatestJarUrl = url;
                }

                ui(() -> {
                    if (ver == null || url == null) {
                        if (game) {
                            status.setText("Could not find latest game .jar asset.");
                            bar.setIndeterminate(false);
                            bar.setValue(0);
                            btnUpdate.setEnabled(false);
                            btnPlay.setEnabled(Files.exists(GAME_JAR));
                        } else btnUpdateLauncher.setEnabled(false);
                        return;
                    }
                    if (game) {
                        boolean newer = isNewer(ver, localVersion) || !Files.exists(GAME_JAR);
                        btnUpdate.setEnabled(newer);
                        btnPlay.setEnabled(Files.exists(GAME_JAR));
                        bar.setIndeterminate(false);
                        bar.setValue(newer ? 0 : 100);
                        if (!newer) bar.setString("Game up to date");
                    } else {
                        boolean newer = isNewer(ver, launcherLocalVersion);
                        btnUpdateLauncher.setEnabled(newer);
                        if (!newer && bar.isIndeterminate()) {
                            bar.setIndeterminate(false);
                            bar.setValue(100);
                            bar.setString("Up to date");
                        }
                    }
                });
            } catch (Exception ex) {
                ui(() -> {
                    if (game) {
                        status.setText("Offline or API error. You can still play offline if installed.");
                        bar.setIndeterminate(false);
                        btnPlay.setEnabled(Files.exists(GAME_JAR));
                        btnUpdate.setEnabled(false);
                    } else btnUpdateLauncher.setEnabled(false);
                });
            }
        });
    }

    private void startUpdate(boolean game) {
        if (game) {
            btnUpdate.setEnabled(false);
            btnPlay.setEnabled(false);
            bar.setIndeterminate(true);
            bar.setString(null);
            status.setText("Downloading game " + latestVersion + "…");
        } else {
            btnUpdateLauncher.setEnabled(false);
            bar.setIndeterminate(true);
            bar.setString(null);
            status.setText("Downloading launcher " + launcherLatestVersion + "…");
        }

        exec.submit(() -> {
            Path tmp = null;
            try {
                String url = game ? latestJarUrl : launcherLatestJarUrl;
                if (url == null) throw new IOException("No " + (game ? "game" : "launcher") + " asset URL.");

                tmp = downloadToTemp(url, game ? "tlob-" : "tlob-launcher-");

                Files.createDirectories(HOME_DIR);

                if (!game) {
                    Path self = getSelfJarPath();
                    if (self != null && Files.isRegularFile(self) && Files.isSameFile(self, LAUNCHER_JAR)) {
                        Path staged = HOME_DIR.resolve("launcher.jar.new");
                        Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                        ui(() -> {
                            status.setText("Updating launcher… restarting.");
                            bar.setIndeterminate(true);
                        });
                        writeAndRunSelfReplaceScript(staged, LAUNCHER_JAR, findJava());
                        ui(() -> {
                            exec.shutdown();
                            setVisible(false);
                            dispose();
                            System.exit(0);
                        });
                        return;
                    }
                    Files.move(tmp, LAUNCHER_JAR, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    ui(() -> {
                        status.setText("Launcher installed. Restarting updated launcher…");
                        bar.setValue(100);
                        bar.setString("Done");
                    });
                    new ProcessBuilder(findJava(), "-jar", LAUNCHER_JAR.toString()).directory(HOME_DIR.toFile()).inheritIO().start();
                    ui(() -> {
                        exec.shutdown();
                        setVisible(false);
                        dispose();
                        System.exit(0);
                    });
                    return;
                }

                // game
                Files.move(tmp, GAME_JAR, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                saveLocalGameVersion(latestVersion);
                ui(() -> {
                    status.setText("Installed game " + latestVersion + ". Ready to play.");
                    bar.setValue(100);
                    bar.setString("Done");
                    btnPlay.setEnabled(true);
                });

            } catch (Exception ex) {
                if (tmp != null) try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
                ui(() -> {
                    if (game) {
                        status.setText("Game download failed: " + ex.getMessage());
                        btnUpdate.setEnabled(true);
                        btnPlay.setEnabled(Files.exists(GAME_JAR));
                    } else {
                        status.setText("Launcher update failed: " + ex.getMessage());
                        btnUpdateLauncher.setEnabled(true);
                    }
                    if (bar.isIndeterminate()) {
                        bar.setIndeterminate(false);
                        bar.setValue(0);
                    }
                });
            }
        });
    }

    // ------------------------ Local versions & launch ------------------------

    private void loadLocalVersion() {
        if (Files.exists(INSTALLED_PROPS)) {
            try (var in = Files.newInputStream(INSTALLED_PROPS)) {
                var p = new Properties();
                p.load(in);
                localVersion = p.getProperty("version", localVersion);
            } catch (IOException ignored) {
            }
        }
        status.setText("Installed game: " + localVersion + " — checking for updates…");
    }

    private void saveLocalGameVersion(String version) {
        try (var out = Files.newOutputStream(INSTALLED_PROPS)) {
            var p = new Properties();
            p.setProperty("version", version);
            p.setProperty("path", GAME_JAR.toString());
            p.store(out, "TLOB installed version");
        } catch (IOException ignored) {
        }
    }

    private void loadLauncherLocalVersion() {
        String v = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        if (v != null && !v.isBlank()) launcherLocalVersion = v.trim();
    }

    private void launchGame() {
        try {
            if (!Files.exists(GAME_JAR)) {
                JOptionPane.showMessageDialog(this, "Game not installed yet.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            status.setText("Launching game…");
            new ProcessBuilder(findJava(), "-jar", GAME_JAR.toString())
                .directory(HOME_DIR.toFile()).inheritIO().start();
            ui(() -> {
                exec.shutdown();
                setVisible(false);
                dispose();
                System.exit(0);
            });
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to start game: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------ Networking / IO helpers ------------------------

    private String httpGetString(String url) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(20)).GET();
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token.trim());
        var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("GitHub API " + resp.statusCode());
        return resp.body();
    }

    /** Downloads the URL to a temp file while updating the progress bar; returns the temp path. */
    private Path downloadToTemp(String url, String prefix) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile(prefix, ".jar.part");
        long total = contentLength(url);
        ui(() -> {
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setValue(0);
                bar.setString(null);
            }
        });

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).GET().build();

        try (OutputStream out = new CountingOutputStream(Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING), total)) {
            http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body().transferTo(out);
        }
        return tmp;
    }

    private class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        private final long total;
        private long read = 0;

        CountingOutputStream(OutputStream out, long total) {
            this.out = out;
            this.total = total;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            update(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            update(len);
        }

        private void update(long bytes) {
            if (total > 0) {
                read += bytes;
                int pct = (int) Math.max(0, Math.min(100, (read * 100) / Math.max(1, total)));
                ui(() -> {
                    bar.setValue(pct);
                    bar.setString(pct + "%");
                });
            }
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private long contentLength(String url) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT).build();
            var resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            String cl = resp.headers().firstValue("Content-Length").orElse(null);
            if (cl != null) return Long.parseLong(cl);
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ------------------------ Misc ------------------------

    private static String findJava() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin", (isWindows() ? "java.exe" : "java"));
        return Files.exists(bin) ? bin.toString() : "java";
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static Path getSelfJarPath() {
        try {
            var url = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return null;
            Path p = Path.of(url.toURI());
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
            new ProcessBuilder("cmd", "/c", "start", "", bat.toString(), staged.toString(), dest.toString(), javaPath)
                .inheritIO().start();
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
                .inheritIO().start();
        }
    }

    // --- Tiny utils (kept inline to reduce file count) ---

    private static boolean isNewer(String remote, String local) {
        String r = remote != null && remote.startsWith("v") ? remote.substring(1) : String.valueOf(remote);
        String l = local != null && local.startsWith("v") ? local.substring(1) : String.valueOf(local);
        String[] a = (r == null ? "0" : r).split("\\.");
        String[] b = (l == null ? "0" : l).split("\\.");
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
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String url = m.group(1);
            if (url.toLowerCase().endsWith(".jar")) return url;
        }
        return null;
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
