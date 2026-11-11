package com.example.clouddisk.task;

import com.example.clouddisk.service.RecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站过期清理。
 *
 * <p>两处触发：
 * <ol>
 *   <li>应用启动就绪后跑一次 —— 服务器可能几天没重启，但每天都该清；
 *       放在启动时补一次，避免「刚好在清理时间点关过机」导致一堆超期文件一直躺着。</li>
 *   <li>每天定时跑一次，时间由 {@code file.recycle.clean-cron} 配置。</li>
 * </ol>
 *
 * <p>清理放在独立线程（Spring 默认调度线程池），失败只记日志，绝不影响应用本身。
 */
@Slf4j
@Component
public class RecycleCleanTask {

    private final RecycleService recycleService;

    /** 保留天数：删除后超过这么多天就彻底删除 */
    @Value("${file.recycle.keep-days:7}")
    private int keepDays;

    public RecycleCleanTask(RecycleService recycleService) {
        this.recycleService = recycleService;
    }

    /** 每天凌晨 3:10 清理（低峰时段，避开上传高峰） */
    @Scheduled(cron = "${file.recycle.clean-cron:0 10 3 * * ?}")
    public void cleanByCron() {
        run("定时任务");
    }

    /** 启动补跑一次：等应用完全就绪（数据源可用）再执行 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanOnStartup() {
        run("启动补跑");
    }

    private void run(String trigger) {
        long start = System.currentTimeMillis();
        try {
            int n = recycleService.purgeExpired(keepDays);
            if (n > 0) {
                log.info("回收站清理完成（{}）：彻底删除 {} 个超过 {} 天的文件，耗时 {} ms",
                        trigger, n, keepDays, System.currentTimeMillis() - start);
            } else {
                log.debug("回收站清理完成（{}）：没有超期文件", trigger);
            }
        } catch (Exception e) {
            // 清理失败不能影响主流程，下次定时任务会再试
            log.error("回收站清理失败（{}）", trigger, e);
        } catch (Throwable t) {
            // Error（如 OOM、NoClassDefFound）也兜住，否则整批清理会静默中断
            log.error("回收站清理抛出非 Exception（{}）", trigger, t);
        }
    }
}
