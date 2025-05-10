package ani.rss.action;

import ani.rss.annotation.Auth;
import ani.rss.annotation.Path;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.task.RssTask;
import ani.rss.util.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.comparator.PinyinComparator;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.pinyin.PinyinUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 订阅 增删改查
 */
@Auth
@Slf4j
@Path("/ani")
public class AniAction implements BaseAction {

    public static final AtomicBoolean DOWNLOAD = new AtomicBoolean(false);

    /**
     * 手动刷新订阅
     */
    private void download() {
        Ani ani = getBody(Ani.class);

        if (Objects.isNull(ani)) {
            RssTask.sync();
            ThreadUtil.execute(() -> RssTask.download(new AtomicBoolean(true)));
            resultSuccessMsg("已开始刷新RSS");
            return;
        }

        Optional<Ani> first = AniUtil.ANI_LIST.stream()
                .filter(it -> it.getId().equals(ani.getId()))
                .findFirst();
        if (first.isEmpty()) {
            resultErrorMsg("修改失败");
            return;
        }
        synchronized (DOWNLOAD) {
            if (DOWNLOAD.get()) {
                resultErrorMsg("存在未完成任务，请等待...");
                return;
            }
            DOWNLOAD.set(true);
        }
        Ani downloadAni = first.get();
        ThreadUtil.execute(() -> {
            try {
                if (TorrentUtil.login()) {
                    TorrentUtil.downloadAni(downloadAni);
                }
            } catch (Exception e) {
                String message = ExceptionUtil.getMessage(e);
                log.error(message, e);
            }
            DOWNLOAD.set(false);
        });
        resultSuccessMsg("已开始刷新RSS {} {}", downloadAni.getTitle(), downloadAni.getUrl());
    }

    /**
     * 添加订阅
     */
    private void post() {
        Ani ani = getBody(Ani.class);
        ani.setTitle(ani.getTitle().trim())
                .setUrl(ani.getUrl().trim());
        AniUtil.verify(ani);

        Optional<Ani> first = AniUtil.ANI_LIST.stream()
                .filter(it -> it.getId().equals(ani.getId()))
                .findFirst();

        if (first.isPresent()) {
            resultErrorMsg("此订阅已存在");
            return;
        }

        first = AniUtil.ANI_LIST.stream()
                .filter(it -> it.getTitle().equals(ani.getTitle()) && it.getSeason().equals(ani.getSeason()))
                .findFirst();

        if (first.isPresent()) {
            resultErrorMsg("名称重复");
            return;
        }

        AniUtil.ANI_LIST.add(ani);
        AniUtil.sync();
        Boolean enable = ani.getEnable();
        if (enable) {
            ThreadUtil.execute(() -> {
                if (TorrentUtil.login()) {
                    TorrentUtil.downloadAni(ani);
                }
            });
        } else {
            // 如果未开启订阅则只获取一下集数
            ThreadUtil.execute(() -> {
                try {
                    List<Item> items = ItemsUtil.getItems(ani);
                    int currentEpisodeNumber = ItemsUtil.currentEpisodeNumber(ani, items);
                    ani.setCurrentEpisodeNumber(currentEpisodeNumber);
                } catch (Exception e) {
                    log.error(ExceptionUtil.getMessage(e), e);
                }
            });
        }
        resultSuccessMsg("添加订阅成功");
        log.info("添加订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());
    }

    /**
     * 修改订阅
     */
    private void put() {
        Ani ani = getBody(Ani.class);
        ani.setTitle(ani.getTitle().trim())
                .setUrl(ani.getUrl().trim());
        AniUtil.verify(ani);
        Optional<Ani> first = AniUtil.ANI_LIST.stream()
                .filter(it -> !it.getId().equals(ani.getId()))
                .filter(it -> it.getTitle().equals(ani.getTitle()) && it.getSeason().equals(ani.getSeason()))
                .findFirst();
        if (first.isPresent()) {
            resultErrorMsg("名称重复");
            return;
        }

        first = AniUtil.ANI_LIST.stream()
                .filter(it -> it.getId().equals(ani.getId()))
                .findFirst();
        if (first.isEmpty()) {
            resultErrorMsg("修改失败");
            return;
        }
        HttpServerRequest request = ServerUtil.REQUEST.get();
        String move = request.getParam("move");
        if (Boolean.parseBoolean(move)) {
            Ani get = first.get();
            ThreadUtil.execute(() -> {
                List<File> downloadPaths = TorrentUtil.getDownloadPath(get);
                File newDownloadPath = TorrentUtil.getDownloadPath(ani).get(0);
                Boolean login = TorrentUtil.login();
                List<TorrentsInfo> torrentsInfos = new ArrayList<>();
                if (login) {
                    torrentsInfos = TorrentUtil.getTorrentsInfos();
                }
                for (File file : downloadPaths) {
                    if (file.toString().equals(newDownloadPath.toString())) {
                        // 位置未发生改变
                        continue;
                    }

                    for (TorrentsInfo torrentsInfo : torrentsInfos) {
                        if (!torrentsInfo.getDownloadDir().equals(file.toString())) {
                            // 旧位置不相同
                            continue;
                        }
                        // 修改保存位置
                        TorrentUtil.setSavePath(torrentsInfo, newDownloadPath.toString());
                    }
                    if (!file.exists()) {
                        continue;
                    }
                    if (file.isFile()) {
                        continue;
                    }
                    ThreadUtil.sleep(3000);
                    FileUtil.mkdir(newDownloadPath);
                    File[] files = ObjectUtil.defaultIfNull(file.listFiles(), new File[]{});
                    for (File oldFile : files) {
                        log.info("移动文件 {} ==> {}", oldFile, newDownloadPath);
                        FileUtil.move(oldFile, newDownloadPath, false);
                    }
                    FileUtil.del(file);
                    ClearCacheAction.clearParentFile(file);
                }

            });
        }
        File torrentDir = TorrentUtil.getTorrentDir(first.get());
        BeanUtil.copyProperties(ani, first.get());
        File newTorrentDir = TorrentUtil.getTorrentDir(first.get());
        if (!torrentDir.toString().equals(newTorrentDir.toString())) {
            FileUtil.move(torrentDir, newTorrentDir.getParentFile(), true);
        }
        AniUtil.sync();
        resultSuccessMsg("修改成功");
        log.info("修改订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());
    }

    /**
     * 返回订阅列表
     */
    private void get() {
        Config config = ConfigUtil.CONFIG;
        Boolean scoreShow = config.getScoreShow();
        // 按拼音排序

        List<Ani> list = AniUtil.ANI_LIST;
        if (scoreShow) {
            list = CollUtil.sort(list, Comparator.comparingDouble(Ani::getScore).reversed());
        } else {
            PinyinComparator pinyinComparator = new PinyinComparator();
            list = CollUtil.sort(list, (a, b) -> pinyinComparator.compare(a.getTitle(), b.getTitle()));
        }

        for (Ani ani : list) {
            String title = ani.getTitle();
            String pinyin = PinyinUtil.getPinyin(title);
            ani.setPinyin(pinyin);

            Integer year = ani.getYear();
            Integer month = ani.getMonth();
            Integer day = ani.getDate();
            DateTime dateTime = DateUtil.parseDate(StrFormatter.format("{}-{}-{}", year, month, day));
            // 0表示周日，1表示周一
            ani.setWeek(DateUtil.dayOfWeek(dateTime) - 1);
        }
        resultSuccess(list);
    }

    /**
     * 删除订阅
     */
    public void delete() {
        JsonArray jsonArray = getBody(JsonArray.class);
        List<String> ids = jsonArray.asList()
                .stream().map(JsonElement::getAsString)
                .toList();
        List<Ani> anis = AniUtil.ANI_LIST.stream()
                .filter(it -> ids.contains(it.getId()))
                .toList();
        if (anis.isEmpty()) {
            resultErrorMsg("删除失败");
            return;
        }
        for (Ani ani : anis) {
            synchronized (AniUtil.ANI_LIST) {
                AniUtil.ANI_LIST.remove(ani);
            }
        }

        AniUtil.sync();
        resultSuccessMsg("删除订阅成功");
        HttpServerRequest request = ServerUtil.REQUEST.get();
        String deleteFiles = request.getParam("deleteFiles");
        ThreadUtil.execute(() -> {
            for (Ani ani : anis) {
                File torrentDir = TorrentUtil.getTorrentDir(ani);
                FileUtil.del(torrentDir);
                ClearCacheAction.clearParentFile(torrentDir);
                log.info("删除订阅 {} {} {}", ani.getTitle(), ani.getUrl(), ani.getId());
            }
            if (!Boolean.parseBoolean(deleteFiles)) {
                // 不删除本地文件
                return;
            }

            List<File> files = anis
                    .stream()
                    .map(TorrentUtil::getDownloadPath)
                    .flatMap(List::stream)
                    .toList();

            Boolean login = TorrentUtil.login();
            List<TorrentsInfo> torrentsInfos = new ArrayList<>();
            if (login) {
                torrentsInfos = TorrentUtil.getTorrentsInfos();
            }
            for (File file : files) {
                List<TorrentsInfo> collect = torrentsInfos
                        .stream()
                        .filter(torrentsInfo -> torrentsInfo.getDownloadDir().equals(file.toString()))
                        .toList();
                for (TorrentsInfo torrentsInfo : collect) {
                    TorrentUtil.delete(torrentsInfo, true, true);
                }
                if (!file.exists()) {
                    continue;
                }
                ThreadUtil.sleep(3000);
                log.info("删除 {}", file);
                FileUtil.del(file);
                ClearCacheAction.clearParentFile(file);
            }
        });
    }

    private void batchEnable(boolean enable) {
        List<String> ids = getBody(JsonArray.class)
                .asList()
                .stream()
                .map(JsonElement::getAsString)
                .toList();
        for (Ani ani : AniUtil.ANI_LIST) {
            String id = ani.getId();
            if (!ids.contains(id)) {
                continue;
            }
            ani.setEnable(enable);
        }
        AniUtil.sync();
        resultSuccessMsg("修改完成");
    }

    @Override
    public void doAction(HttpServerRequest req, HttpServerResponse res) {
        String method = req.getMethod();
        String type = StrUtil.blankToDefault(req.getParam("type"), "");
        if (type.equals("download")) {
            download();
            return;
        }

        if (type.equals("batchEnable")) {
            boolean enable = Boolean.parseBoolean(req.getParam("value"));
            batchEnable(enable);
            return;
        }

        switch (method) {
            case "POST": {
                post();
                return;
            }
            case "PUT": {
                put();
                return;
            }
            case "GET": {
                get();
                return;
            }
            case "DELETE": {
                delete();
                break;
            }
        }
    }
}
