package com.taotao.music.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Windows 稳定启动器。它不加载应用类，因此可以在应用 JAR 损坏时先完成自动回滚。
 */
public final class LauncherMain {
    private static final String REGISTRY_KEY = "HKCU\\Software\\TaotaoMusic";
    private static final String ATTEMPT_VALUE = "UpdateAttempts";

    private LauncherMain() {}

    public static void main(String[] args) throws Exception {
        Path root = installationRoot();
        boolean allowPending = args.length >= 2 && "--allow-pending-version".equals(args[0]);
        if (Registry.readDword(ATTEMPT_VALUE) > 0 && !allowPending) {
            rollback(root);
            Registry.writeDword(ATTEMPT_VALUE, 0);
        }
        Path current = root.resolve("current");
        Path runtime = root.resolve("runtime").resolve("bin").resolve("java.exe");
        if (!Files.isRegularFile(runtime)) runtime = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(runtime)) throw new IllegalStateException("找不到 Java 21 运行时：" + runtime);
        if (!Files.isDirectory(current)) throw new IllegalStateException("安装目录缺少 current：" + current);

        List<String> command = new ArrayList<>();
        command.add(runtime.toString());
        command.add("-Dtaotao.install.root=" + root);
        command.add("-Dtaotao.launched.by.stable.launcher=true");
        command.add("-cp");
        command.add(current.resolve("*").toString());
        command.add("com.taotao.music.desktop.MainKt");
        Process app = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        System.exit(app.waitFor());
    }

    private static Path installationRoot() {
        String configured = System.getProperty("taotao.install.root", "").trim();
        if (!configured.isEmpty()) return Path.of(configured).toAbsolutePath().normalize();
        try {
            Path location = Path.of(LauncherMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return (Files.isDirectory(location) ? location : location.getParent()).toAbsolutePath().normalize();
        } catch (Exception error) {
            throw new IllegalStateException("无法确定安装目录", error);
        }
    }

    private static void rollback(Path root) throws IOException {
        Path current = root.resolve("current");
        Path backup = root.resolve("backup");
        if (!Files.isDirectory(backup)) return;
        Path failed = root.resolve("failed-" + System.currentTimeMillis());
        if (Files.exists(current)) move(current, failed);
        move(backup, current);
        deleteRecursively(failed);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class Registry {
        private Registry() {}

        static int readDword(String name) {
            try {
                Process process = new ProcessBuilder("reg.exe", "query", REGISTRY_KEY, "/v", name)
                    .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                if (process.waitFor() != 0) return 0;
                String token = output.trim().replaceAll("(?s).*REG_DWORD\\s+", "").trim().split("\\s+")[0];
                return token.startsWith("0x") ? Integer.parseUnsignedInt(token.substring(2), 16) : Integer.parseInt(token);
            } catch (Exception ignored) {
                return 0;
            }
        }

        static void writeDword(String name, int value) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(
                "reg.exe", "add", REGISTRY_KEY, "/v", name, "/t", "REG_DWORD", "/d", String.valueOf(value), "/f"
            ).inheritIO().start();
            if (process.waitFor() != 0) throw new IOException("写入更新注册表状态失败");
        }
    }
}
