package ani.rss.util;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.MessageEnum;
import ani.rss.enums.TorrentsTags;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpConfig;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Alist
 */
@Slf4j
public class AlistUtil {
    private static final ExecutorService EXECUTOR = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();

    /**
     * 将下载完成的任务上传至Alist
     *
     * @param torrentsInfo 任务
     */
    public static void upload(TorrentsInfo torrentsInfo, Ani ani) {
        Boolean upload = Opt.ofNullable(ani)
                .map(Ani::getUpload)
                .orElse(true);
        // 禁止自动上传
        if (!upload) {
            return;
        }

        Config config = ConfigUtil.CONFIG;
        Boolean alist = config.getAlist();
        if (!alist) {
            return;
        }
        String alistHost = config.getAlistHost();
        String alistToken = config.getAlistToken();
        Integer alistRetry = config.getAlistRetry();

        verify();

        List<String> tags = torrentsInfo.getTags();
        if (tags.contains(TorrentsTags.A_LIST.getValue())) {
            return;
        }

        TorrentUtil.addTags(torrentsInfo, TorrentsTags.A_LIST.getValue());

        String downloadDir = FilePathUtil.getAbsolutePath(torrentsInfo.getDownloadDir());

        List<String> files = torrentsInfo.getFiles().get();
        String filePath = getPath(torrentsInfo, ani);
        for (String fileName : files) {
            String finalFilePath = filePath + "/" + fileName;
            File file = new File(downloadDir + "/" + fileName);
            if (!file.exists()) {
                log.error("文件不存在 {}", file);
                return;
            }

            EXECUTOR.execute(() -> {
                log.info("上传 {} ==> {}", file, finalFilePath);
                for (int i = 0; i < alistRetry; i++) {
                    try {
                        String url = alistHost;
                        if (url.endsWith("/")) {
                            url = url.substring(0, url.length() - 1);
                        }
                        // 使用流式上传
                        url += "/api/fs/form";

                        // 50M 上传
                        HttpConfig httpConfig = new HttpConfig()
                                .setBlockSize(1024 * 1024 * 50);

                        HttpReq
                                .put(url, false)
                                .timeout(1000 * 60 * 2)
                                .setConfig(httpConfig)
                                .header(Header.AUTHORIZATION, alistToken)
                                .header("As-Task", "true")
                                .header("File-Path", URLUtil.encode(finalFilePath))
                                .header(Header.CONTENT_LENGTH, String.valueOf(file.length()))
                                .form("file", file)
                                .then(res -> {
                                    Assert.isTrue(res.isOk(), "上传失败 {} 状态码:{}", fileName, res.getStatus());
                                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                                    int code = jsonObject.get("code").getAsInt();
                                    String taskID = jsonObject.getAsJsonObject("task").get("id").getAsString();
                                    uploadMonitor(ani,taskID, alistHost, alistToken, fileName);
                                    log.info(jsonObject.toString());
                                    Assert.isTrue(code == 200, "上传失败 {} 状态码:{}", fileName, code);
                                    String text = StrFormatter.format("已向 alist 添加上传任务 {}", fileName);
                                    log.info(text);
                                    MessageUtil.send(config, ani, text, MessageEnum.ALIST_UPLOAD);
                                });
                        return;
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }
                if (AfdianUtil.verifyExpirationTime()) {
                    MessageUtil.send(config, ani, "alist上传失败 " + fileName, MessageEnum.ERROR);
                }
            });
        }
    }

    /**
     * 刷新 Alist 路径
     */
    public static void refresh(TorrentsInfo torrentsInfo, Ani ani) {
        Config config = ConfigUtil.CONFIG;
        Boolean refresh = config.getAlistRefresh();
        if (!refresh) {
            return;
        }
        String alistHost = config.getAlistHost();
        String alistToken = config.getAlistToken();

        verify();

        String finalPath = getPath(torrentsInfo, ani);
        String rootPath = getRootPath(ani) + "/";
        EXECUTOR.execute(() -> {
            Long getAlistRefreshDelay = config.getAlistRefreshDelayed();
            if (getAlistRefreshDelay > 0) {
                ThreadUtil.sleep(getAlistRefreshDelay, TimeUnit.SECONDS);
            }
            log.info("刷新 Alist 路径: {}", finalPath);

            try {
                String url = alistHost;
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                url += "/api/fs/list";

                Map<String, Object> resolved = Map.of(
                        "path", finalPath,
                        "refresh", true);
                Map<String, Object> root = Map.of(
                        "path", rootPath,
                        "refresh", true);
                HttpReq.post(url)
                        .timeout(1000 * 20)
                        .header(Header.AUTHORIZATION, alistToken)
                        .body(GsonStatic.toJson(root))
                        .then(res -> {
                            Assert.isTrue(res.isOk(), "刷新失败 路径: {} 状态码: {}", rootPath, res.getStatus());
                            JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                            int code = jsonObject.get("code").getAsInt();
                            Assert.isTrue(code == 200, "刷新失败 路径: {} 状态码: {}", rootPath, code);
                        });
                HttpReq.post(url)
                        .timeout(1000 * 20)
                        .header(Header.AUTHORIZATION, alistToken)
                        .body(GsonStatic.toJson(resolved))
                        .then(res -> {
                            Assert.isTrue(res.isOk(), "刷新失败 路径: {} 状态码: {}", finalPath, res.getStatus());
                            JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                            int code = jsonObject.get("code").getAsInt();
                            Assert.isTrue(code == 200, "刷新失败 路径: {} 状态码: {}", finalPath, code);
                            log.info("已成功刷新 Alist 路径: {}", finalPath);
                        });
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            ThreadUtil.sleep(3000);
        });
    }

    public static void verify() {
        Config config = ConfigUtil.CONFIG;
        String alistHost = config.getAlistHost();
        String alistPath = FilePathUtil.getAbsolutePath(config.getAlistPath());
        String alistToken = config.getAlistToken();

        Assert.notBlank(alistHost, "alistHost 未配置");
        Assert.notBlank(alistPath, "alistPath 未配置");
        Assert.notBlank(alistToken, "alistToken 未配置");
    }

    private static String getPath(TorrentsInfo torrentsInfo, Ani ani) {
        Config config = ConfigUtil.CONFIG;
        String downloadDir = FilePathUtil.getAbsolutePath(torrentsInfo.getDownloadDir());
        String downloadPath = FilePathUtil.getAbsolutePath(config.getDownloadPath());
        String ovaDownloadPath = FilePathUtil.getAbsolutePath(config.getOvaDownloadPath());
        String filePath = getRootPath(ani);

        if (StrUtil.isNotBlank(downloadPath) && downloadDir.startsWith(downloadPath)) {
            filePath += downloadDir.substring(downloadPath.length());
        } else if (StrUtil.isNotBlank(ovaDownloadPath) && downloadDir.startsWith(ovaDownloadPath)) {
            filePath += downloadDir.substring(ovaDownloadPath.length());
        } else {
            filePath += downloadDir;
        }
        return filePath;
    }

    private static String getRootPath(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        String alistOvaPath = FilePathUtil.getAbsolutePath(config.getAlistOvaPath());
        String filePath = FilePathUtil.getAbsolutePath(config.getAlistPath());

        Boolean ova = Opt.ofNullable(ani)
                .map(Ani::getOva)
                .orElse(false);

        if (ova) {
            filePath = StrUtil.blankToDefault(alistOvaPath, filePath);
        }
        if (filePath.endsWith("/")) {
            filePath = filePath.substring(0, filePath.length() - 1);

        }
        return filePath;
    }

    private static void uploadMonitor(Ani ani, String taskID, String alistHost, String alistToken, String fileName) {
        EXECUTOR.execute(() -> {
            AtomicBoolean isCompleted = new AtomicBoolean(false);
            long retryDelay = 1000 * 60;
            int maxRetry = 10;
            String url = alistHost;
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            url += "/api/admin/task/upload/info?tid=" + taskID;
            try {
                AtomicInteger retry = new AtomicInteger(0);
                while (!isCompleted.get() && retry.get() < maxRetry) {
                    Thread.sleep(retryDelay);
                    HttpReq.post(url)
                            .timeout(1000 * 20)
                            .header(Header.AUTHORIZATION, alistToken)
                            .then(res -> {
                                JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                                JsonObject data = jsonObject.getAsJsonArray("data").get(0).getAsJsonObject();
                                int code = jsonObject.get("code").getAsInt();
                                if (code != 200) {
                                    retry.incrementAndGet();
                                    log.warn("上传任务 {} 状态码异常，当前重试次数: {}/{}", fileName, retry.get(), maxRetry);
                                } else {
                                    retry.set(0);
                                    String state = data.get("state").getAsString();
                                    if ("successed".equals(state)) {
                                        String text = StrFormatter.format("已向 alist 上传完成 {}", fileName);
                                        log.info(text);
                                        Config config = ConfigUtil.CONFIG;
                                        MessageUtil.send(config, ani, text, MessageEnum.ALIST_UPLOAD);
                                        isCompleted.set(true);
                                    }
                                }
                                ;
                            });
                }
                Assert.isTrue(isCompleted.get(), "上传失败 {}", fileName);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        });
    }
}
