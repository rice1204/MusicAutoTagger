package com.lux032.musicautotagger.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：{@link DirectoryUtils#ensureExists}。
 *
 * <p>覆盖此前生产环境反复出现的真实故障：
 * {@code File.mkdirs()} 在底层网络存储（SMB/CIFS）瞬时故障时返回 {@code false}，
 * 而旧代码完全忽略该返回值——目录实际未创建，但调用方以为已经创建成功，紧接着
 * 对目录内文件执行 {@code Files.copy}，最终抛出 {@code NoSuchFileException}
 * 且永久失败（TagWriterService / CoverArtCache / FailedFileHandler 中均出现过
 * 完全相同的模式）。
 */
class DirectoryUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDirectoryWhenMissing() throws IOException {
        File dir = tempDir.resolve("a/b/c").toFile();
        assertFalse(dir.exists());

        DirectoryUtils.ensureExists(dir);

        assertTrue(dir.isDirectory(), "目录应已被创建");
    }

    @Test
    void isNoOpWhenDirectoryAlreadyExists() throws IOException {
        File dir = tempDir.resolve("already-there").toFile();
        assertTrue(dir.mkdirs());

        // 不应抛出异常，也不应产生副作用
        DirectoryUtils.ensureExists(dir);

        assertTrue(dir.isDirectory());
    }

    @Test
    void throwsIOExceptionInsteadOfSilentlyProceeding_whenPathIsBlockedByAFile() throws IOException {
        // 模拟 mkdirs() 永久失败的场景：路径中某一段已经是一个普通文件，
        // 不可能被创建为目录。旧实现会忽略 mkdirs() 的 false 返回值，
        // 让调用方以为目录已就绪，随后对其中的文件执行拷贝/写入时才会
        // 遭遇 NoSuchFileException。新实现应该在这里就显式抛出异常，
        // 不允许调用方继续在一个实际不存在的目录上执行写入。
        File blockingFile = tempDir.resolve("blocker").toFile();
        assertTrue(blockingFile.createNewFile());

        File impossibleDir = new File(blockingFile, "child-dir");

        IOException ex = assertThrows(IOException.class,
            () -> DirectoryUtils.ensureExists(impossibleDir, 3, 10L));

        assertTrue(ex.getMessage().contains("创建目录失败"));
        assertFalse(impossibleDir.exists());
    }

    @Test
    void succeedsIfDirectoryAppearsBetweenRetries() throws Exception {
        // 模拟瞬时故障场景：第一次检查时目录不存在，但在重试等待期间
        // （例如另一个并发线程，或底层存储恢复后）目录被创建出来。
        // 新实现应当把这种情况当作成功，而不是继续重试或报错。
        File dir = tempDir.resolve("appears-later").toFile();
        assertFalse(dir.exists());

        Thread creator = new Thread(() -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
            }
            dir.mkdirs();
        });
        creator.start();

        DirectoryUtils.ensureExists(dir, 5, 20L);
        creator.join();

        assertTrue(dir.isDirectory());
    }
}
