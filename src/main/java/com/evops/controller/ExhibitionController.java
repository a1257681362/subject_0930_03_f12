package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ExhibitionCloseRequest;
import com.evops.dto.ExhibitionCreateRequest;
import com.evops.dto.ExhibitionHallAttachRequest;
import com.evops.dto.StocktakeReplayRequest;
import com.evops.dto.StocktakeRoundCreateRequest;
import com.evops.entity.Exhibition;
import com.evops.entity.Hall;
import com.evops.entity.StocktakeRound;
import com.evops.service.ExhibitionService;
import com.evops.service.StocktakeLedgerService;
import com.evops.service.StocktakeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 展览与清点版本（museum-12 展览借品清点盘点）。
 * 一个展览含多个展厅；先旧数据台账回放（REPLAY），再生成正式现场版本
 * （LIVE，标题"展览借品清点盘点"）；全部复核确认前不能关闭展览。
 */
@RestController
@RequestMapping("/api")
public class ExhibitionController {

    private final ExhibitionService exhibitionService;
    private final StocktakeService stocktakeService;
    private final StocktakeLedgerService ledgerService;

    public ExhibitionController(ExhibitionService exhibitionService,
                                StocktakeService stocktakeService,
                                StocktakeLedgerService ledgerService) {
        this.exhibitionService = exhibitionService;
        this.stocktakeService = stocktakeService;
        this.ledgerService = ledgerService;
    }

    /** 建展（PLANNED） */
    @PostMapping("/exhibitions")
    public ApiResponse<Exhibition> create(@Valid @RequestBody ExhibitionCreateRequest request) {
        return ApiResponse.ok(exhibitionService.create(request));
    }

    /** 展览列表 */
    @GetMapping("/exhibitions")
    public ApiResponse<List<Exhibition>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(exhibitionService.list(status));
    }

    /** 展览详情（含挂载展厅与全部清点版本） */
    @GetMapping("/exhibitions/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> bundle = exhibitionService.detail(id);
        bundle.put("rounds", stocktakeService.roundsOfExhibition(id));
        return ApiResponse.ok(bundle);
    }

    /** 开放展览（开放后才能生成清点版本） */
    @PostMapping("/exhibitions/{id}/open")
    public ApiResponse<Exhibition> open(@PathVariable Long id) {
        return ApiResponse.ok(exhibitionService.open(id));
    }

    /** 关闭展览：全部清点版本关闭/取代前硬阻断 */
    @PostMapping("/exhibitions/{id}/close")
    public ApiResponse<Exhibition> close(@PathVariable Long id,
                                         @Valid @RequestBody ExhibitionCloseRequest request) {
        return ApiResponse.ok(exhibitionService.close(id, request));
    }

    /** 挂载展厅 */
    @PostMapping("/exhibitions/{id}/halls")
    public ApiResponse<List<Hall>> attachHalls(@PathVariable Long id,
                                               @Valid @RequestBody ExhibitionHallAttachRequest request) {
        return ApiResponse.ok(exhibitionService.attachHalls(id, request));
    }

    /** 生成正式现场版本（可带 fromRoundId 重建版本，搬运已接受扫描） */
    @PostMapping("/exhibitions/{id}/rounds")
    public ApiResponse<Map<String, Object>> createRound(
            @PathVariable Long id,
            @Valid @RequestBody StocktakeRoundCreateRequest request) {
        return ApiResponse.ok(stocktakeService.createLiveRound(id, request));
    }

    /** 旧数据台账回放（requestNo 幂等，差异自动引用台账解释） */
    @PostMapping("/exhibitions/{id}/rounds/replay")
    public ApiResponse<Map<String, Object>> replay(
            @PathVariable Long id,
            @Valid @RequestBody StocktakeReplayRequest request) {
        return ApiResponse.ok(stocktakeService.replay(id, request));
    }

    /** 展览的全部清点版本 */
    @GetMapping("/exhibitions/{id}/rounds")
    public ApiResponse<List<StocktakeRound>> rounds(@PathVariable Long id) {
        return ApiResponse.ok(stocktakeService.roundsOfExhibition(id));
    }

    /** 版本详情（含各展厅任务计数、补充记录） */
    @GetMapping("/stocktake-rounds/{id}")
    public ApiResponse<Map<String, Object>> roundDetail(@PathVariable Long id) {
        return ApiResponse.ok(stocktakeService.roundDetail(id));
    }

    /** 关闭版本：全部任务复核 + 全部差异确认 */
    @PostMapping("/stocktake-rounds/{id}/close")
    public ApiResponse<StocktakeRound> closeRound(
            @PathVariable Long id,
            @Valid @RequestBody com.evops.dto.RoundCloseRequest request) {
        return ApiResponse.ok(
                stocktakeService.closeRound(id, request.getClosedBy(), request.getRemark()));
    }

    /** 关闭版本后追加补充记录（原结果不可变） */
    @PostMapping("/stocktake-rounds/{id}/supplements")
    public ApiResponse<Map<String, Object>> addSupplement(
            @PathVariable Long id,
            @Valid @RequestBody com.evops.dto.SupplementRequest request) {
        return ApiResponse.ok(java.util.Collections.singletonMap(
                "supplement", stocktakeService.addSupplement(id, request)));
    }

    /** 补充记录上传照片 */
    @PostMapping(value = "/stocktake-rounds/{id}/supplements/{suppId}/photos",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.evops.entity.StocktakeSupplementPhoto> uploadSupplementPhoto(
            @PathVariable Long id,
            @PathVariable Long suppId,
            @org.springframework.web.bind.annotation.RequestParam("file")
            org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam String uploadedBy,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String remark) {
        return ApiResponse.ok(
                stocktakeService.uploadSupplementPhoto(id, suppId, file, uploadedBy, remark));
    }

    /** 清点台账查询 */
    @GetMapping("/stocktake-ledgers")
    public ApiResponse<List<com.evops.entity.StocktakeLedger>> ledgers(
            @RequestParam(required = false) Long exhibitionId,
            @RequestParam(required = false) Long roundId,
            @RequestParam(required = false) Long hallId) {
        return ApiResponse.ok(ledgerService.list(exhibitionId, roundId, hallId));
    }
}
