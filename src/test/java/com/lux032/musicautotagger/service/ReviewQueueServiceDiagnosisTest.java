package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewDiagnosis;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 结构化原因与搜索：面板靠它们回答「这个文件为什么没被自动处理、它在队列的哪里」。
 */
class ReviewQueueServiceDiagnosisTest {
    @TempDir Path tempDir;
    private MusicConfig config;
    private ReviewQueueService queue;
    private Path albumDir;
    private File audioFile;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        queue = new ReviewQueueService(config);
        albumDir = Files.createDirectories(tempDir.resolve("Best of 2024"));
        audioFile = Files.createFile(albumDir.resolve("03. Track Three.flac")).toFile();
    }

    private ReviewItem enqueue(ReviewDiagnosis diagnosis) {
        MusicMetadata md = new MusicMetadata();
        md.setTitle("夜に駆ける");
        md.setArtist("YOASOBI");
        md.setDuration(261);
        FolderAlbumCache.PendingFile pending = new FolderAlbumCache.PendingFile(audioFile, audioFile, null, md, null);
        return queue.enqueue(albumDir.toString(), List.of(pending), null, new ArrayList<>(),
            List.of(261), "专辑无法确定", 0.4, diagnosis);
    }

    @Test
    void diagnosisCodeAndFactsSurviveRestart() {
        enqueue(ReviewDiagnosis.of(ReviewDiagnosis.NO_DOMINANT_ALBUM)
            .with("coverage", 0.25)
            .with("albumCount", 7)
            .with("bestTitle", null));

        ReviewItem item = new ReviewQueueService(config).list(ReviewItem.Status.PENDING_REVIEW).get(0);
        assertEquals(ReviewDiagnosis.NO_DOMINANT_ALBUM, item.getReasonCode());
        assertEquals(0.25, ((Number) item.getReasonFacts().get("coverage")).doubleValue());
        assertEquals(7, ((Number) item.getReasonFacts().get("albumCount")).intValue());
        assertEquals(false, item.getReasonFacts().containsKey("bestTitle"), "缺失的事实不落盘");
    }

    @Test
    void missingDiagnosisFallsBackToGenericCode() {
        ReviewItem item = enqueue(null);
        assertEquals(ReviewDiagnosis.ALBUM_UNDETERMINED, item.getReasonCode());
        // 旧接口不带诊断，同样落到兜底原因
        assertEquals(ReviewDiagnosis.ALBUM_UNDETERMINED, ReviewDiagnosis.effectiveCode(item));
    }

    @Test
    void legacyItemsWithoutCodeAreClassifiedByKindAndSource() {
        ReviewItem legacy = new ReviewItem();
        assertEquals(ReviewDiagnosis.ALBUM_UNDETERMINED, ReviewDiagnosis.effectiveCode(legacy));

        legacy.setRecoverySourceType("FAILED");
        assertEquals(ReviewDiagnosis.ONLINE_ASSIST, ReviewDiagnosis.effectiveCode(legacy));

        legacy.setKind(ReviewItem.Kind.TRACKS_MISSING);
        assertEquals(ReviewDiagnosis.TRACKS_MISSING, ReviewDiagnosis.effectiveCode(legacy));
    }

    @Test
    void searchFindsItemByFolderFileTitleOrArtist() {
        ReviewItem item = enqueue(null);

        assertEquals("", ReviewQueueService.matchQuery(item, null), "没有搜索词时全部命中");
        assertEquals("", ReviewQueueService.matchQuery(item, "  "));
        assertEquals("", ReviewQueueService.matchQuery(item, "best OF"), "文件夹名不区分大小写");
        assertEquals("03. Track Three.flac", ReviewQueueService.matchQuery(item, "track three"));
        assertEquals("03. Track Three.flac · 夜に駆ける", ReviewQueueService.matchQuery(item, "夜に"),
            "按曲名命中时带上曲名，否则看不出为什么匹配到这个文件");
        assertEquals("03. Track Three.flac · 夜に駆ける", ReviewQueueService.matchQuery(item, "yoasobi"));
        assertNull(ReviewQueueService.matchQuery(item, "不存在的文件"));
    }
}
