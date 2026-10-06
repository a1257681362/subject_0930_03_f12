package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.DiffConfirmRequest;
import com.evops.dto.DiffExplainRequest;
import com.evops.dto.ScanSubmitRequest;
import com.evops.dto.StocktakeReviewRequest;
import com.evops.dto.StocktakeSubmitRequest;
import com.evops.entity.StocktakeDiff;
import com.evops.entity.StocktakeDiffPhoto;
import com.evops.entity.StocktakeAttempt;
import com.evops.entity.StocktakeTask;
import com.evops.service.StocktakeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 清点任务与差异处理（museum-12）。
 * 扫描箱号+展签（版本冲突即拒绝且不落数据）→ 提交生成缺失/错位/状态异常差异
 * → 差异现场照片+双见证人+解释 → 逐条确认 → 任务复核。
 */
@RestController
@RequestMapping("/api")
public class StocktakeController {

    private final StocktakeService stocktakeService;

    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    /** 清点任务列表（可按版本/展厅/状态过滤） */
    @GetMapping("/stocktake-tasks")
    public ApiResponse<List<StocktakeTask>> listTasks(@RequestParam(required = false) Long roundId,
                                                      @RequestParam(required = false) Long hallId,
                                                      @RequestParam(required = false) String status) {
        return ApiResponse.ok(stocktakeService.listTasks(roundId, hallId, status));
    }

    /** 任务详情：基准行、扫描批次/行、差异/照片、补充记录 */
    @GetMapping("/stocktake-tasks/{id}")
    public ApiResponse<Map<String, Object>> taskDetail(@PathVariable Long id) {
        return ApiResponse.ok(stocktakeService.taskDetail(id));
    }

    /** 版本冲突预检：返回冻结指纹、当前指纹与逐项变更清单 */
    @GetMapping("/stocktake-tasks/{id}/baseline-changes")
    public ApiResponse<Map<String, Object>> baselineChanges(@PathVariable Long id) {
        return ApiResponse.ok(stocktakeService.baselineChanges(id));
    }

    /** 扫描批次提交（箱号+展签；requestNo 幂等；基准变更报版本冲突） */
    @PostMapping("/stocktake-tasks/{id}/scans")
    public ApiResponse<Map<String, Object>> submitScans(
            @PathVariable Long id,
            @Valid @RequestBody ScanSubmitRequest request) {
        return ApiResponse.ok(stocktakeService.submitScans(id, request));
    }

    /** 最终提交：逐行比对冻结基准，生成三类差异，进入待复核 */
    @PostMapping("/stocktake-tasks/{id}/submit")
    public ApiResponse<Map<String, Object>> submitTask(
            @PathVariable Long id,
            @Valid @RequestBody StocktakeSubmitRequest request) {
        return ApiResponse.ok(stocktakeService.submitTask(id, request));
    }

    /** 任务复核：差异必须能解释；现场差异还须照片与两名不同见证人 */
    @PostMapping("/stocktake-tasks/{id}/review")
    public ApiResponse<StocktakeTask> review(@PathVariable Long id,
                                             @Valid @RequestBody StocktakeReviewRequest request) {
        return ApiResponse.ok(stocktakeService.reviewTask(id, request));
    }

    /** 差异关联现场照片（内容寻址去重，至少一张） */
    @PostMapping(value = "/stocktake-diffs/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<StocktakeDiffPhoto> uploadDiffPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam String uploadedBy,
            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(stocktakeService.uploadDiffPhoto(id, file, uploadedBy, remark));
    }

    /** 差异解释（人工必填；LIVE 须先有照片+两名不同见证人；回放差异免见证人） */
    @PostMapping("/stocktake-diffs/{id}/explain")
    public ApiResponse<StocktakeDiff> explain(@PathVariable Long id,
                                              @Valid @RequestBody DiffExplainRequest request) {
        return ApiResponse.ok(stocktakeService.explainDiff(id, request));
    }

    /** 差异逐条确认 */
    @PostMapping("/stocktake-diffs/{id}/confirm")
    public ApiResponse<StocktakeDiff> confirm(@PathVariable Long id,
                                              @Valid @RequestBody DiffConfirmRequest request) {
        return ApiResponse.ok(stocktakeService.confirmDiff(id, request));
    }

    /** 清点操作尝试审计（生效/被拒绝/版本冲突，可按版本/任务/操作过滤） */
    @GetMapping("/stocktake-attempts")
    public ApiResponse<List<StocktakeAttempt>> attempts(@RequestParam(required = false) Long roundId,
                                                        @RequestParam(required = false) Long taskId,
                                                        @RequestParam(required = false) String operation) {
        return ApiResponse.ok(stocktakeService.listAttempts(roundId, taskId, operation));
    }
}
