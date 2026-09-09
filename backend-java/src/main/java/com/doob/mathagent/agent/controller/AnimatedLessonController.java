package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.service.AnimatedLessonService;
import com.doob.mathagent.agent.service.AnimatedLessonService.AnimatedLessonSubmission;
import com.doob.mathagent.agent.service.AnimatedLessonService.AnimatedLessonView;
import com.doob.mathagent.agent.worker.AgentWorkerTask;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 「动画讲题」对外 API：创建任务、轮询 meta、按 HTTP Range 分发渲染视频。
 *
 * <p>鉴权与讲义一致：身份只由 RequestSubjectResolver 从后端会话解析，任务可见性经
 * AnimatedLessonService.findOwned 的 workflow 行校验（tenant + subject，admin 例外），
 * 因此他人 taskId 与不存在 taskId 都收敛为同一个 404，不泄露存在性。长任务语义下本控制器
 * 只入队与读取，绝不在 HTTP 线程里等待 Python 渲染。</p>
 */
@RestController
public class AnimatedLessonController {

    /** 渲染产物固定为 mp4；播放器对明确类型嗅探最稳，octet-stream 在部分 WebView 里会拒绝内联播放。 */
    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

    private final AnimatedLessonService animatedLessonService;
    private final RequestSubjectResolver subjectResolver;

    public AnimatedLessonController(
            AnimatedLessonService animatedLessonService,
            RequestSubjectResolver subjectResolver) {
        this.animatedLessonService = animatedLessonService;
        this.subjectResolver = subjectResolver;
    }

    /** 排队一个动画讲题任务，立即返回轮询句柄 taskId；渲染为分钟级长任务。 */
    @PostMapping("/api/animated-lessons/tasks")
    public Map<String, Object> create(
            @RequestBody AnimatedLessonSubmission request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            AgentWorkerTask task = animatedLessonService.submit(subject, request);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", task.taskId());
            response.put("workflowId", task.workflowId());
            response.put("status", task.status());
            return response;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 轮询接口：status 为 workflow 行状态（RUNNING/COMPLETED/FAILED），errorSummary 携带
     * Agent Worker 的重试失败摘要；result 是 Python 响应原文（chapters/problem/durationSec 等），
     * 未完成时为 null。前端轮询间隔 ≥5 秒即可。
     */
    @GetMapping("/api/animated-lessons/{taskId}/meta")
    public Map<String, Object> meta(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        AnimatedLessonView view = requireView(taskId, httpRequest);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", view.task().taskId());
        response.put("workflowId", view.workflow().workflowId());
        response.put("status", view.workflow().status());
        response.put("errorSummary", view.task().errorSummary());
        response.put("attempt", view.task().attempt());
        response.put("createdAt", view.workflow().createdAt().toString());
        response.put("updatedAt", view.workflow().updatedAt().toString());
        // result 为 JsonNode，直接透传 worker 响应原文，Java 不重新映射任何教学字段。
        response.put("result", animatedLessonService.resultJson(view.workflow()));
        return response;
    }

    /**
     * 流式分发渲染视频，支持播放器拖拽所需的单区间 Range/206。
     *
     * <p>仓库内此前没有 Range 先例（讲义 PDF 是 byte[] 全量下载），因此采用 Spring 官方
     * HttpRange + ResourceRegion 原语而非手写字节拷贝；ResourceRegionHttpMessageConverter 会
     * 自动补 Content-Range/Content-Length，控制器只负责裁区间与置 206。文件只从
     * AnimatedLessonService.videoFile 的受控目录解析，绝不接受请求方提供的路径。</p>
     */
    @GetMapping("/api/animated-lessons/{taskId}/video")
    public ResponseEntity<?> video(
            @PathVariable String taskId,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest httpRequest) throws IOException {
        // contentLength() 在 Spring 6.2 声明 IOException；文件缺失/不可读交给 404 处理器即可，
        // 不值得在流式端点里包一层假语义。
        AnimatedLessonView view = requireView(taskId, httpRequest);
        Path file = animatedLessonService.videoFile(view.workflow())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Animated lesson video not found"));
        FileSystemResource resource = new FileSystemResource(file);
        long length = resource.contentLength();
        if (length <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Animated lesson video not found");
        }
        List<HttpRange> ranges = headers.getRange();
        if (ranges.isEmpty()) {
            // 无 Range：全量 200 直接交 ResourceHttpMessageConverter 流式写出，避免给整文件挂 Content-Range。
            return ResponseEntity.ok()
                    .contentType(VIDEO_MP4)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body((Resource) resource);
        }
        // 播放器与浏览器均为单区间请求；多区间只服务第一段，语义与静态资源容器一致。
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        long regionLength = Math.max(1L, end - start + 1L);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(VIDEO_MP4)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new ResourceRegion(resource, start, regionLength));
    }

    private AnimatedLessonView requireView(String taskId, HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        return normalizedTaskId(taskId)
                .flatMap(id -> animatedLessonService.findOwned(id, subject))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Animated lesson task not found"));
    }

    /** 与讲义 workflow id 同款的形态校验：taskId 是 UUID 形态，先拒畸形输入再查库。 */
    private static Optional<String> normalizedTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) {
            return Optional.empty();
        }
        return Optional.of(taskId);
    }
}
