package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 目录创建相关的工具方法。
 *
 * <p>背景：{@link File#mkdirs()} 在创建失败时返回 {@code false} 而不会抛出异常。
 * 本项目中多处代码（标签写入、封面缓存、失败文件归档等）此前都是直接调用
 * {@code dir.mkdirs()} 并忽略其返回值，然后立即对目录内的文件执行写入操作。
 *
 * <p>当目标目录位于网络存储（如 SMB/CIFS、NFS 挂载）之上，且该存储在负载较高
 * 或短暂抖动时，{@code mkdirs()} 可能瞬时失败。由于返回值被忽略，调用方会误以为
 * 目录已经创建成功，紧接着对目录内文件执行 {@code Files.copy} / {@code Files.write}，
 * 从而遭遇 {@code NoSuchFileException}，导致文件处理永久失败——即使重试整个处理
 * 流程通常也能成功，因为网络存储的故障本身是瞬时的。
 *
 * <p>本工具类提供一个带指数退避重试的目录创建方法，将原本"静默忽略失败"的行为
 * 改为"重试后仍失败则显式抛出异常"，以便调用方能够将其纳入正常的失败处理 / 重试
 * 队列逻辑，而不是在目录实际未创建成功的情况下继续向下执行。
 */
@Slf4j
public final class DirectoryUtils {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_BASE_DELAY_MS = 200L;

    private DirectoryUtils() {
    }

    /**
     * 确保目录存在，必要时创建；创建失败时按指数退避重试，多次重试后仍失败则抛出
     * {@link IOException}。
     *
     * @param dir 需要确保存在的目录
     * @throws IOException 当目录在多次重试后仍无法创建时抛出
     */
    public static void ensureExists(File dir) throws IOException {
        ensureExists(dir, DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MS);
    }

    /**
     * 确保目录存在，必要时创建；创建失败时按指数退避重试，多次重试后仍失败则抛出
     * {@link IOException}。
     *
     * @param dir         需要确保存在的目录
     * @param maxAttempts 最大尝试次数（含首次尝试）
     * @param baseDelayMs 重试之间的基础等待时间（毫秒），按 2^n 指数退避
     * @throws IOException 当目录在多次重试后仍无法创建时抛出
     */
    public static void ensureExists(File dir, int maxAttempts, long baseDelayMs) throws IOException {
        if (dir == null) {
            throw new IOException("目标目录为 null");
        }
        if (dir.isDirectory()) {
            return;
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean created = dir.mkdirs();
            // mkdirs() 在并发创建（另一线程同时创建了同一目录）时也会返回 false，
            // 因此除了检查返回值，还要再确认一次目录是否真实存在。
            if (created || dir.isDirectory()) {
                if (attempt > 1) {
                    log.info("目录创建成功（第 {} 次尝试）: {}", attempt, dir.getAbsolutePath());
                }
                return;
            }

            lastError = new IOException("mkdirs() 返回 false: " + dir.getAbsolutePath());
            log.warn("目录创建失败，准备重试 ({}/{}): {}", attempt, maxAttempts, dir.getAbsolutePath());

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(baseDelayMs * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("创建目录时被中断: " + dir.getAbsolutePath(), ie);
                }
            }
        }

        throw new IOException(
            "创建目录失败，已重试 " + maxAttempts + " 次仍未成功: " + dir.getAbsolutePath(), lastError);
    }
}
