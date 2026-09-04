package com.taotao.music.updater;

import io.sigpipe.jbsdiff.Patch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;

/** 等待应用退出，应用完整文件或 bsdiff 补丁，再由稳定启动器启动新版本。 */
public final class UpdaterMain {
    private static final String REGISTRY_KEY = "HKCU\\Software\\TaotaoMusic";

    private UpdaterMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("用法：updater <install-root> <plan.properties> <app-pid>");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path planFile = Path.of(args[1]).toAbsolutePath().normalize();
        long appPid = Long.parseLong(args[2]);
        ProcessHandle.of(appPid).ifPresent(process -> process.onExit().join());

        Properties plan = new Properties();
        try (var input = Files.newInputStream(planFile)) { plan.load(input); }
        int versionCode = Integer.parseInt(required(plan, "versionCode"));
        Path staging = checkedChild(root, required(plan, "staging"));
        Path prepared = root.resolve("prepared-" + versionCode);
        deleteRecursively(prepared);
        Files.createDirectories(prepared);

        int count = Integer.parseInt(required(plan, "file.count"));
        for (int index = 0; index < count; index++) {
            String prefix = "file." + index + ".";
            String logical = required(plan, prefix + "path");
            Path destination = checkedChild(prepared, logical);
            Files.createDirectories(destination.getParent());
            String mode = required(plan, prefix + "mode");
            if ("patch".equals(mode)) {
                Path oldFile = checkedChild(root.resolve("current"), logical);
                Path patchFile = checkedChild(staging, required(plan, prefix + "source"));
                String algorithm = required(plan, prefix + "algorithm");
                if ("courgette".equals(algorithm)) {
                    Path courgette = root.resolve("tools").resolve("courgette.exe");
                    if (!Files.isRegularFile(courgette)) throw new IOException("安装目录缺少 Courgette 工具");
                    Process process = new ProcessBuilder(
                        courgette.toString(), "-apply", oldFile.toString(), patchFile.toString(), destination.toString()
                    ).inheritIO().start();
                    if (process.waitFor() != 0) throw new IOException("Courgette 应用补丁失败");
                } else {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    Patch.patch(Files.readAllBytes(oldFile), Files.readAllBytes(patchFile), output);
                    Files.write(destination, output.toByteArray());
                }
            } else if ("reuse".equals(mode)) {
                Files.copy(checkedChild(root.resolve("current"), logical), destination);
            } else {
                Files.copy(checkedChild(staging, required(plan, prefix + "source")), destination);
            }
            String actual = sha256(destination);
            if (!actual.equals(required(plan, prefix + "sha256"))) {
                throw new IOException("更新文件校验失败：" + logical);
            }
        }

        Registry.writeAttempt(1);
        Path current = root.resolve("current");
        Path backup = root.resolve("backup");
        deleteRecursively(backup);
        if (Files.exists(current)) move(current, backup);
        try {
            move(prepared, current);
        } catch (Throwable error) {
            if (Files.exists(backup) && !Files.exists(current)) move(backup, current);
            Registry.writeAttempt(0);
            throw error;
        }
        deleteRecursively(staging);
        Files.deleteIfExists(planFile);

        Path launcherExe = root.resolve("launcher.exe");
        ProcessBuilder builder;
        if (Files.isRegularFile(launcherExe)) {
            builder = new ProcessBuilder(launcherExe.toString(), "--allow-pending-version", String.valueOf(versionCode));
        } else {
            Path launcherJar = root.resolve("taotao-launcher.jar");
            builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dtaotao.install.root=" + root,
                "-jar", launcherJar.toString(), "--allow-pending-version", String.valueOf(versionCode)
            );
        }
        builder.directory(root.toFile()).start();
    }

    private static Path checkedChild(Path root, String relative) {
        Path target = root.resolve(relative.replace('\\', '/')).normalize();
        if (!target.startsWith(root.normalize())) throw new IllegalArgumentException("路径越界：" + relative);
        return target;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("更新计划缺少 " + key);
        return value;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class Registry {
        static void writeAttempt(int value) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(
                "reg.exe", "add", REGISTRY_KEY, "/v", "UpdateAttempts", "/t", "REG_DWORD", "/d", String.valueOf(value), "/f"
            ).inheritIO().start();
            if (process.waitFor() != 0) throw new IOException("写入更新尝试计数失败");
        }
    }
}
