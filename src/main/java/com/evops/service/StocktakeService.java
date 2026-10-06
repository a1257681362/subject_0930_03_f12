package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.DiffConfirmRequest;
import com.evops.dto.DiffExplainRequest;
import com.evops.dto.ScanItemDto;
import com.evops.dto.ScanSubmitRequest;
import com.evops.dto.StocktakeReplayRequest;
import com.evops.dto.StocktakeReviewRequest;
import com.evops.dto.StocktakeRoundCreateRequest;
import com.evops.dto.StocktakeSubmitRequest;
import com.evops.dto.SupplementRequest;
import com.evops.entity.Exhibition;
import com.evops.entity.ExhibitionHall;
import com.evops.entity.Hall;
import com.evops.entity.HallRelic;
import com.evops.entity.LedgerEntry;
import com.evops.entity.PhotoFile;
import com.evops.entity.Relic;
import com.evops.entity.RelicLoan;
import com.evops.entity.RepairProject;
import com.evops.entity.StocktakeAttempt;
import com.evops.entity.StocktakeBaselineLine;
import com.evops.entity.StocktakeDiff;
import com.evops.entity.StocktakeDiffPhoto;
import com.evops.entity.StocktakeRound;
import com.evops.entity.StocktakeScanBatch;
import com.evops.entity.StocktakeScanLine;
import com.evops.entity.StocktakeSupplement;
import com.evops.entity.StocktakeSupplementPhoto;
import com.evops.entity.StocktakeTask;
import com.evops.entity.StorageStock;
import com.evops.mapper.ExhibitionHallMapper;
import com.evops.mapper.ExhibitionMapper;
import com.evops.mapper.HallMapper;
import com.evops.mapper.HallRelicMapper;
import com.evops.mapper.PhotoFileMapper;
import com.evops.mapper.RelicLoanMapper;
import com.evops.mapper.RelicMapper;
import com.evops.mapper.RepairProjectMapper;
import com.evops.mapper.StocktakeAttemptMapper;
import com.evops.mapper.StocktakeBaselineLineMapper;
import com.evops.mapper.StocktakeDiffMapper;
import com.evops.mapper.StocktakeDiffPhotoMapper;
import com.evops.mapper.StocktakeRoundMapper;
import com.evops.mapper.StocktakeScanBatchMapper;
import com.evops.mapper.StocktakeScanLineMapper;
import com.evops.mapper.StocktakeSupplementMapper;
import com.evops.mapper.StocktakeSupplementPhotoMapper;
import com.evops.mapper.StocktakeTaskMapper;
import com.evops.mapper.StorageStockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 展览借品清点盘点核心服务（museum-12）。
 *
 * <p>按展厅冻结清点基准（在陈记录+文物状态+未还借出+未结修复+在库箱号）并算
 * SHA-256 指纹；现场扫描箱号+展签，提交时逐行比对出缺失/错位/状态异常三类差异。
 * 清点期间借还/陈展/箱位变化使指纹变化时，扫描与提交报版本冲突、不写入数据，
 * 可重建版本（rebase）搬运已接受扫描。</p>
 *
 * <p>旧数据可先回放成 REPLAY 版本：每条差异自动引用 t_relic_ledger 台账解释；
 * 无台账依据的差异保留为无解释，硬阻断复核。LIVE 差异必须有解释、≥1 张现场
 * 照片与两名不同见证人。全部任务复核、全部差异确认后才能关版本，进而关展览。
 * 关闭后结果不可变，只能追加补充记录。</p>
 *
 * <p>写操作经 request_no 幂等，锁前快查、锁内复查、回放原结果/原拒绝原因，
 * 每次尝试落 t_stocktake_attempt；被拒尝试在 REQUIRES_NEW 独立事务保留。</p>
 */
@Service
public class StocktakeService {

    private final ExhibitionMapper exhibitionMapper;
    private final ExhibitionHallMapper exhibitionHallMapper;
    private final HallMapper hallMapper;
    private final HallRelicMapper hallRelicMapper;
    private final RelicMapper relicMapper;
    private final RelicLoanMapper relicLoanMapper;
    private final RepairProjectMapper repairProjectMapper;
    private final StorageStockMapper storageStockMapper;
    private final StocktakeRoundMapper roundMapper;
    private final StocktakeTaskMapper taskMapper;
    private final StocktakeBaselineLineMapper baselineMapper;
    private final StocktakeScanBatchMapper batchMapper;
    private final StocktakeScanLineMapper scanLineMapper;
    private final StocktakeDiffMapper diffMapper;
    private final StocktakeDiffPhotoMapper diffPhotoMapper;
    private final StocktakeSupplementMapper supplementMapper;
    private final StocktakeSupplementPhotoMapper supplementPhotoMapper;
    private final StocktakeAttemptMapper attemptMapper;
    private final PhotoFileMapper photoFileMapper;
    private final ImageStorage imageStorage;
    private final LedgerService relicLedgerService;
    private final StocktakeLedgerService ledgerService;
    private final StocktakeAttemptAuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public StocktakeService(ExhibitionMapper exhibitionMapper,
                            ExhibitionHallMapper exhibitionHallMapper,
                            HallMapper hallMapper,
                            HallRelicMapper hallRelicMapper,
                            RelicMapper relicMapper,
                            RelicLoanMapper relicLoanMapper,
                            RepairProjectMapper repairProjectMapper,
                            StorageStockMapper storageStockMapper,
                            StocktakeRoundMapper roundMapper,
                            StocktakeTaskMapper taskMapper,
                            StocktakeBaselineLineMapper baselineMapper,
                            StocktakeScanBatchMapper batchMapper,
                            StocktakeScanLineMapper scanLineMapper,
                            StocktakeDiffMapper diffMapper,
                            StocktakeDiffPhotoMapper diffPhotoMapper,
                            StocktakeSupplementMapper supplementMapper,
                            StocktakeSupplementPhotoMapper supplementPhotoMapper,
                            StocktakeAttemptMapper attemptMapper,
                            PhotoFileMapper photoFileMapper,
                            ImageStorage imageStorage,
                            LedgerService relicLedgerService,
                            StocktakeLedgerService ledgerService,
                            StocktakeAttemptAuditService auditService,
                            PlatformTransactionManager transactionManager) {
        this.exhibitionMapper = exhibitionMapper;
        this.exhibitionHallMapper = exhibitionHallMapper;
        this.hallMapper = hallMapper;
        this.hallRelicMapper = hallRelicMapper;
        this.relicMapper = relicMapper;
        this.relicLoanMapper = relicLoanMapper;
        this.repairProjectMapper = repairProjectMapper;
        this.storageStockMapper = storageStockMapper;
        this.roundMapper = roundMapper;
        this.taskMapper = taskMapper;
        this.baselineMapper = baselineMapper;
        this.batchMapper = batchMapper;
        this.scanLineMapper = scanLineMapper;
        this.diffMapper = diffMapper;
        this.diffPhotoMapper = diffPhotoMapper;
        this.supplementMapper = supplementMapper;
        this.supplementPhotoMapper = supplementPhotoMapper;
        this.attemptMapper = attemptMapper;
        this.photoFileMapper = photoFileMapper;
        this.imageStorage = imageStorage;
        this.relicLedgerService = relicLedgerService;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================================================================
    // 版本生成 / 回放 / rebase
    // ==================================================================

    /** 生成正式现场清点版本 LIVE（标题默认"展览借品清点盘点"）；fromRoundId 非空=rebase */
    public Map<String, Object> createLiveRound(Long exhibitionId, StocktakeRoundCreateRequest req) {
        String requestNo = trimToNull(req.getRequestNo());
        StocktakeAttempt fast = findAttempt(requestNo);
        if (fast != null) {
            return roundBundle(replayRound(fast), true);
        }
        try {
            return transactionTemplate.execute(status -> {
                Exhibition exhibition = lockExhibition(exhibitionId);
                requireOpen(exhibition);
                StocktakeAttempt raced = findAttempt(requestNo);
                if (raced != null) {
                    return roundBundle(replayRound(raced), true);
                }
                LocalDateTime now = LocalDateTime.now();
                StocktakeRound source = null;
                if (req.getFromRoundId() != null) {
                    source = roundMapper.selectById(req.getFromRoundId());
                    if (source == null || !source.getExhibitionId().equals(exhibitionId)) {
                        throw new IllegalArgumentException("重建来源清点版本不存在或不属于该展览");
                    }
                    roundMapper.lockRow(source.getId());
                    source = roundMapper.selectById(source.getId());
                    if (!ROUND_FROZEN.equals(source.getStatus())) {
                        throw new IllegalArgumentException("来源清点版本状态为 " + source.getStatus()
                                + "，只有清点中的版本可以重建");
                    }
                }
                int versionNo = nextVersionNo(exhibitionId);
                StocktakeRound round = new StocktakeRound();
                round.setRoundNo(String.format("SR-%06d", roundMapper.nextRoundSeq()));
                round.setExhibitionId(exhibitionId);
                round.setVersionNo(versionNo);
                round.setKind(ROUND_LIVE);
                round.setTitle(trimToEmpty(req.getTitle()) == null
                        ? ROUND_TITLE_DEFAULT
                        : (req.getTitle().trim().isEmpty() ? ROUND_TITLE_DEFAULT : req.getTitle().trim()));
                round.setStatus(ROUND_FROZEN);
                round.setFrozenAt(now);
                round.setGeneratedBy(req.getGeneratedBy().trim());
                round.setSourceRoundId(source == null ? null : source.getId());
                round.setRequestNo(requestNo);
                round.setBaselineFingerprint(sha256("FREEZING:" + round.getRoundNo()));
                roundMapper.insert(round);

                List<Long> hallIds = exhibitionHallIds(exhibitionId);
                if (hallIds.isEmpty()) {
                    throw new IllegalArgumentException("展览尚未挂载展厅，不能生成清点版本");
                }
                List<StocktakeTask> tasks = freezeTasks(round, hallIds, now);
                round.setBaselineFingerprint(aggregateFingerprint(tasks));
                roundMapper.updateById(round);

                if (source != null) {
                    carryScans(source, round, tasks, now);
                    markSuperseded(source, round, req.getGeneratedBy(), now);
                }

                insertAttempt(round, null, ST_OP_ROUND_CREATE, requestNo,
                        req.getGeneratedBy(), round.getId(), now);
                ledgerService.append(exhibitionId, round.getId(), null, null,
                        source == null ? ACTION_ST_ROUND_CREATE : ACTION_ST_SUPERSEDE,
                        round.getRoundNo(),
                        (source == null ? "生成现场清点版本 " : "基于版本 #" + source.getVersionNo()
                                + " 重建现场清点版本 ") + round.getRoundNo()
                                + "（v" + versionNo + "，" + tasks.size() + " 个展厅任务，标题："
                                + round.getTitle() + "）", now);
                return roundBundle(round, false);
            });
        } catch (IllegalArgumentException e) {
            auditService.recordRejected(null, null, ST_OP_ROUND_CREATE, requestNo,
                    req.getGeneratedBy(), e.getMessage(), exhibitionId, null);
            throw e;
        }
    }

    /** 旧数据台账回放：幂等重建 REPLAY 版本，差异自动引用台账解释 */
    public Map<String, Object> replay(Long exhibitionId, StocktakeReplayRequest req) {
        String requestNo = req.getRequestNo().trim();
        StocktakeAttempt fast = findAttempt(requestNo);
        if (fast != null) {
            return roundBundle(replayRound(fast), true);
        }
        StocktakeRound existingRound = roundMapper.selectOne(
                new LambdaQueryWrapper<StocktakeRound>()
                        .eq(StocktakeRound::getRequestNo, requestNo));
        if (existingRound != null) {
            return roundBundle(existingRound, true);
        }
        try {
            return transactionTemplate.execute(status -> {
                Exhibition exhibition = lockExhibition(exhibitionId);
                requireOpen(exhibition);
                StocktakeAttempt raced = findAttempt(requestNo);
                if (raced != null) {
                    return roundBundle(replayRound(raced), true);
                }
                LocalDateTime now = LocalDateTime.now();
                int versionNo = nextVersionNo(exhibitionId);
                StocktakeRound round = new StocktakeRound();
                round.setRoundNo(String.format("SR-%06d", roundMapper.nextRoundSeq()));
                round.setExhibitionId(exhibitionId);
                round.setVersionNo(versionNo);
                round.setKind(ROUND_REPLAY);
                String title = trimToEmpty(req.getTitle());
                round.setTitle(title == null ? "旧数据台账回放" : title);
                round.setStatus(ROUND_FROZEN);
                round.setFrozenAt(now);
                round.setGeneratedBy(req.getReplayedBy().trim());
                round.setRequestNo(requestNo);
                round.setBaselineFingerprint(sha256("FREEZING:" + round.getRoundNo()));
                roundMapper.insert(round);

                List<Long> hallIds = exhibitionHallIds(exhibitionId);
                if (hallIds.isEmpty()) {
                    throw new IllegalArgumentException("展览尚未挂载展厅，不能回放清点版本");
                }
                List<StocktakeTask> tasks = freezeTasks(round, hallIds, now);
                round.setBaselineFingerprint(aggregateFingerprint(tasks));
                roundMapper.updateById(round);

                for (StocktakeTask task : tasks) {
                    replayTask(round, task, req.getReplayedBy(), now);
                }

                insertAttempt(round, null, ST_OP_REPLAY, requestNo,
                        req.getReplayedBy(), round.getId(), now);
                ledgerService.append(exhibitionId, round.getId(), null, null,
                        ACTION_ST_REPLAY, round.getRoundNo(),
                        "旧数据台账回放生成清点版本 " + round.getRoundNo()
                                + "（v" + versionNo + "，" + tasks.size() + " 个展厅任务）", now);
                return roundBundle(round, false);
            });
        } catch (IllegalArgumentException e) {
            auditService.recordRejected(null, null, ST_OP_REPLAY, requestNo,
                    req.getReplayedBy(), e.getMessage(), exhibitionId, null);
            throw e;
        }
    }

    private void replayTask(StocktakeRound round, StocktakeTask task,
                            String operator, LocalDateTime now) {
        List<StocktakeBaselineLine> lines = baselineLines(task.getId());
        int diffNo = 0;
        int missing = 0;
        int abnormal = 0;
        for (StocktakeBaselineLine line : lines) {
            if (line.getExpected() == null || line.getExpected() == 0) {
                continue;
            }
            if (LOAN_LENT.equals(line.getLoanStatus())) {
                StocktakeDiff diff = newDiff(round, task, ++diffNo, DIFF_MISSING,
                        line, REASON_NOT_SCANNED, "旧数据回放：文物借出未还，现场缺失");
                LedgerEvidence ev = latestLendingEvidence(line.getRelicId(), line.getLoanNo(), now);
                if (ev != null) {
                    applyReplayExplanation(diff, "依据 t_relic_ledger#" + ev.entry.getId()
                            + "（动作 " + ev.entry.getAction() + "，单号 " + ev.entry.getBizNo()
                            + "，" + ev.entry.getOccurredAt() + "）借出未还，故缺失", ev);
                } else {
                    diff.setReasonCode(REASON_NO_LEDGER_BASIS);
                    diff.setReasonDetail("旧数据回放：文物处于借出状态但查不到借出台账依据，"
                            + "须人工解释后方可复核");
                }
                diffMapper.insert(diff);
                missing++;
            } else if (line.getOpenRepairFlag() != null && line.getOpenRepairFlag() == 1) {
                StocktakeDiff diff = newDiff(round, task, ++diffNo, DIFF_STATUS_ABNORMAL,
                        line, REASON_IN_REPAIR,
                        "旧数据回放：文物存在未结修复项目 " + line.getOpenRepairNos());
                LedgerEvidence ev = latestRepairEvidence(line.getRelicId(),
                        line.getOpenRepairNos(), now);
                if (ev != null) {
                    applyReplayExplanation(diff, "依据 t_relic_ledger#" + ev.entry.getId()
                            + "（动作 " + ev.entry.getAction() + "，单号 " + ev.entry.getBizNo()
                            + "，" + ev.entry.getOccurredAt() + "）项目尚未结项，状态异常", ev);
                }
                diffMapper.insert(diff);
                abnormal++;
            } else if (REVOKED.equals(line.getRelicStatus())) {
                StocktakeDiff diff = newDiff(round, task, ++diffNo, DIFF_STATUS_ABNORMAL,
                        line, REASON_REVOKED, "旧数据回放：文物已撤销但仍在在陈基准中");
                LedgerEvidence ev = latestAction(line.getRelicId(), ACTION_REVOKE, null, now);
                if (ev != null) {
                    applyReplayExplanation(diff, "依据 t_relic_ledger#" + ev.entry.getId()
                            + "（动作 " + ev.entry.getAction() + "，单号 " + ev.entry.getBizNo()
                            + "，" + ev.entry.getOccurredAt() + "）文物已撤销，状态异常", ev);
                }
                diffMapper.insert(diff);
                abnormal++;
            }
        }
        task.setMissingCount(missing);
        task.setMisplacedCount(0);
        task.setAbnormalCount(abnormal);
        task.setScannedCount(0);
        task.setStatus(ST_TASK_SUBMITTED);
        task.setSubmittedBy(operator);
        task.setSubmittedAt(now);
        taskMapper.updateById(task);
    }

    private void applyReplayExplanation(StocktakeDiff diff, String text, LedgerEvidence ev) {
        diff.setAutoExplained(1);
        diff.setExplanation(text);
        diff.setExplanationSource(EXPLAIN_REPLAY_LEDGER);
        diff.setSourceRef("t_relic_ledger#" + ev.entry.getId() + " " + ev.entry.getAction()
                + " " + ev.entry.getBizNo() + " " + ev.entry.getOccurredAt());
        diff.setExplainedBy("台账回放");
        diff.setExplainedAt(ev.entry.getOccurredAt());
    }

    // ==================================================================
    // 扫描批次
    // ==================================================================

    /** 扫描批次提交：requestNo 幂等；提交前重算指纹检测版本冲突 */
    public Map<String, Object> submitScans(Long taskId, ScanSubmitRequest req) {
        // 批内重复（箱号+展签）直接拒绝
        Set<String> seen = new HashSet<>();
        for (ScanItemDto item : req.getScans()) {
            if (Boolean.TRUE.equals(item.getConditionAbnormal())
                    && (item.getNote() == null || item.getNote().trim().isEmpty())) {
                throw new IllegalArgumentException("扫描记录 " + item.getBoxNo() + "/"
                        + item.getLabelNo() + " 标记了状态异常，必须填写现场异常说明");
            }
            String key = item.getBoxNo().trim() + "|" + item.getLabelNo().trim();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("同一批次内存在重复扫描：箱号 " + item.getBoxNo()
                        + "、展签 " + item.getLabelNo());
            }
        }
        String requestNo = req.getRequestNo().trim();
        StocktakeScanBatch fast = batchMapper.selectOne(
                new LambdaQueryWrapper<StocktakeScanBatch>().eq(StocktakeScanBatch::getRequestNo, requestNo));
        if (fast != null) {
            return scanBundle(fast, true);
        }
        try {
            return transactionTemplate.execute(status -> {
                taskMapper.lockRow(taskId);
                StocktakeScanBatch raced = batchMapper.selectOne(
                        new LambdaQueryWrapper<StocktakeScanBatch>()
                                .eq(StocktakeScanBatch::getRequestNo, requestNo));
                if (raced != null) {
                    return scanBundle(raced, true);
                }
                StocktakeTask task = requireTask(taskId);
                StocktakeRound round = requireRound(task.getRoundId());
                if (ST_TASK_SUPERSEDED.equals(task.getStatus())
                        || ROUND_SUPERSEDED.equals(round.getStatus())) {
                    throw new IllegalArgumentException("清点版本已被新版本取代，不能继续扫描，"
                            + "请在新版本任务上操作");
                }
                if (!ST_TASK_PENDING.equals(task.getStatus())) {
                    throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                            + " 当前状态为 " + task.getStatus() + "，不能继续扫描");
                }
                List<BaselineChange> changes = detectChanges(task);
                if (!changes.isEmpty()) {
                    throw new IllegalArgumentException(conflictMessage(changes));
                }

                LocalDateTime now = LocalDateTime.now();
                StocktakeScanBatch batch = new StocktakeScanBatch();
                batch.setTaskId(taskId);
                batch.setRequestNo(requestNo);
                batch.setScannedBy(req.getScannedBy().trim());
                batch.setScannedAt(req.getScannedAt() == null ? now : req.getScannedAt());
                batch.setScanPointCode(req.getScanPointCode());
                batch.setRemark(req.getRemark());
                batchMapper.insert(batch);

                int seq = 0;
                for (ScanItemDto item : req.getScans()) {
                    StocktakeScanLine line = new StocktakeScanLine();
                    line.setBatchId(batch.getId());
                    line.setTaskId(taskId);
                    line.setSeqNo(++seq);
                    line.setBoxNo(item.getBoxNo().trim());
                    line.setLabelNo(item.getLabelNo().trim());
                    line.setScanPointCode(item.getScanPointCode() != null
                            ? item.getScanPointCode() : req.getScanPointCode());
                    line.setConditionAbnormal(Boolean.TRUE.equals(item.getConditionAbnormal()) ? 1 : 0);
                    line.setNote(item.getNote());
                    scanLineMapper.insert(line);
                }

                insertAttempt(round, task, ST_OP_SCAN, requestNo,
                        req.getScannedBy(), batch.getId(), now);
                ledgerService.append(round.getExhibitionId(), round.getId(), taskId,
                        task.getHallId(), ACTION_ST_SCAN, batch.getRequestNo(),
                        "展厅 " + task.getHallNo() + " 接收扫描批次（流水号 " + requestNo
                                + "），共 " + req.getScans().size() + " 条，扫描人 "
                                + req.getScannedBy(), now);
                return scanBundle(batch, false);
            });
        } catch (IllegalArgumentException e) {
            StocktakeTask task = taskMapper.selectById(taskId);
            Long roundId = task == null ? null : task.getRoundId();
            Long exhId = roundId == null ? null : requireRound(roundId).getExhibitionId();
            auditService.recordRejected(roundId, taskId, ST_OP_SCAN, requestNo,
                    req.getScannedBy(), e.getMessage(), exhId,
                    task == null ? null : task.getHallId());
            throw e;
        }
    }

    /** 最终提交：去重后逐行比对冻结基准，生成三类差异 */
    public Map<String, Object> submitTask(Long taskId, StocktakeSubmitRequest req) {
        String requestNo = req.getRequestNo().trim();
        StocktakeAttempt fast = findAttempt(requestNo);
        if (fast != null) {
            return Collections.singletonMap("task", replayTaskEntity(fast, taskId));
        }
        try {
            return transactionTemplate.execute(status -> {
                taskMapper.lockRow(taskId);
                StocktakeAttempt raced = findAttempt(requestNo);
                if (raced != null) {
                    return Collections.singletonMap("task", replayTaskEntity(raced, taskId));
                }
                StocktakeTask task = requireTask(taskId);
                StocktakeRound round = requireRound(task.getRoundId());
                if (ST_TASK_SUPERSEDED.equals(task.getStatus())
                        || ROUND_SUPERSEDED.equals(round.getStatus())) {
                    throw new IllegalArgumentException("清点版本已被新版本取代，不能提交，"
                            + "请在新版本任务上操作");
                }
                if (!ST_TASK_PENDING.equals(task.getStatus())) {
                    throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                            + " 当前状态为 " + task.getStatus() + "，不能重复提交");
                }
                List<BaselineChange> changes = detectChanges(task);
                if (!changes.isEmpty()) {
                    throw new IllegalArgumentException(conflictMessage(changes));
                }

                LocalDateTime now =
                        req.getSubmittedAt() == null ? LocalDateTime.now() : req.getSubmittedAt();
                MatchResult result = matchScans(round, task);

                int diffNo = diffMapper.selectCount(new LambdaQueryWrapper<StocktakeDiff>()
                        .eq(StocktakeDiff::getTaskId, taskId)).intValue();
                for (StocktakeDiff diff : result.diffs) {
                    diff.setDiffNo(++diffNo);
                    diffMapper.insert(diff);
                }

                task.setScannedCount(result.scannedCount);
                task.setMissingCount(countType(result.diffs, DIFF_MISSING));
                task.setMisplacedCount(countType(result.diffs, DIFF_MISPLACED));
                task.setAbnormalCount(countType(result.diffs, DIFF_STATUS_ABNORMAL));
                task.setStatus(ST_TASK_SUBMITTED);
                task.setSubmittedBy(req.getSubmittedBy().trim());
                task.setSubmittedAt(now);
                taskMapper.updateById(task);

                insertAttempt(round, task, ST_OP_FINISH, requestNo,
                        req.getSubmittedBy(), task.getId(), now);
                ledgerService.append(round.getExhibitionId(), round.getId(), taskId,
                        task.getHallId(), ACTION_ST_SUBMIT, task.getTaskNo(),
                        "展厅 " + task.getHallNo() + " 清点提交：有效扫描 " + result.scannedCount
                                + " 条，缺失 " + task.getMissingCount() + "、错位 "
                                + task.getMisplacedCount() + "、状态异常 "
                                + task.getAbnormalCount() + "，提交人 " + req.getSubmittedBy(), now);
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("task", task);
                bundle.put("diffs", result.diffs);
                return bundle;
            });
        } catch (IllegalArgumentException e) {
            StocktakeTask task = taskMapper.selectById(taskId);
            Long roundId = task == null ? null : task.getRoundId();
            Long exhId = roundId == null ? null : requireRound(roundId).getExhibitionId();
            auditService.recordRejected(roundId, taskId, ST_OP_FINISH, requestNo,
                    req.getSubmittedBy(), e.getMessage(), exhId,
                    task == null ? null : task.getHallId());
            throw e;
        }
    }

    // ==================================================================
    // 扫描匹配
    // ==================================================================

    private static class MatchResult {
        int scannedCount;
        List<StocktakeDiff> diffs = new ArrayList<>();
    }

    private MatchResult matchScans(StocktakeRound round, StocktakeTask task) {
        MatchResult result = new MatchResult();
        List<StocktakeBaselineLine> lines = baselineLines(task.getId());
        List<StocktakeBaselineLine> expected = lines.stream()
                .filter(l -> l.getExpected() != null && l.getExpected() == 1)
                .collect(Collectors.toList());
        List<StocktakeBaselineLine> withdrawn = lines.stream()
                .filter(l -> l.getExpected() == null || l.getExpected() == 0)
                .collect(Collectors.toList());

        // 本厅在陈展签 → 基准行（展签可能被复用，故为 List）
        Map<String, List<StocktakeBaselineLine>> labelIndex = new HashMap<>();
        for (StocktakeBaselineLine l : expected) {
            if (l.getDisplayNo() != null && !l.getDisplayNo().trim().isEmpty()) {
                labelIndex.computeIfAbsent(l.getDisplayNo().trim(), k -> new ArrayList<>()).add(l);
            }
        }

        // 本展览其他展厅的在陈展签
        List<Long> hallIds = exhibitionHallIds(round.getExhibitionId());
        Map<String, HallRelic> otherHallLabel = new HashMap<>();
        for (Long otherHallId : hallIds) {
            if (otherHallId.equals(task.getHallId())) {
                continue;
            }
            for (HallRelic hr : hallRelicMapper.selectList(new LambdaQueryWrapper<HallRelic>()
                    .eq(HallRelic::getHallId, otherHallId)
                    .eq(HallRelic::getStatus, DISPLAY_ON))) {
                if (hr.getDisplayNo() != null && !hr.getDisplayNo().trim().isEmpty()) {
                    otherHallLabel.putIfAbsent(hr.getDisplayNo().trim(), hr);
                }
            }
        }

        // 扫描与基准涉及箱号 → 当前库存
        Set<String> boxNos = new HashSet<>();
        for (StocktakeBaselineLine l : lines) {
            splitCsv(l.getBoxNo()).forEach(boxNos::add);
        }
        List<StocktakeScanLine> rawScans = scanLineMapper.selectList(
                new LambdaQueryWrapper<StocktakeScanLine>()
                        .eq(StocktakeScanLine::getTaskId, task.getId())
                        .orderByAsc(StocktakeScanLine::getBatchId)
                        .orderByAsc(StocktakeScanLine::getId));
        for (StocktakeScanLine sl : rawScans) {
            boxNos.add(sl.getBoxNo());
        }
        Map<String, StorageStock> stockByBox = new HashMap<>();
        if (!boxNos.isEmpty()) {
            for (StorageStock stock : storageStockMapper.selectList(
                    new LambdaQueryWrapper<StorageStock>().in(StorageStock::getBoxNo, boxNos))) {
                stockByBox.put(stock.getBoxNo(), stock);
            }
        }

        // 跨批去重：同（箱号，展签）只保留最早一条
        Map<String, StocktakeScanLine> uniqueScans = new LinkedHashMap<>();
        for (StocktakeScanLine sl : rawScans) {
            uniqueScans.putIfAbsent(sl.getBoxNo() + "|" + sl.getLabelNo(), sl);
        }
        result.scannedCount = uniqueScans.size();

        Set<Long> matchedLineIds = new HashSet<>();
        for (StocktakeScanLine scan : uniqueScans.values()) {
            String label = scan.getLabelNo();
            String box = scan.getBoxNo();
            String point = scan.getScanPointCode();
            List<StocktakeBaselineLine> here = labelIndex.get(label);

            if (here != null && here.size() >= 2) {
                // 展签在本厅被多个在陈记录复用，不臆测归属
                for (StocktakeBaselineLine l : here) {
                    matchedLineIds.add(l.getId());
                }
                String candidates = here.stream()
                        .map(l -> l.getRelicNo() + "(" + l.getPointCode() + ")")
                        .collect(Collectors.joining(","));
                StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, here.get(0),
                        REASON_LABEL_REUSED, "展签 " + label + " 在本厅对应多个在陈文物："
                                + candidates + "，无法判定归属，请现场核对");
                diff.setFoundBoxNo(box);
                diff.setFoundLabelNo(label);
                diff.setFoundPointCode(point);
                result.diffs.add(diff);
                continue;
            }

            if (here != null && here.size() == 1) {
                StocktakeBaselineLine line = here.get(0);
                matchedLineIds.add(line.getId());
                StorageStock stock = stockByBox.get(box);

                if (stock != null && stock.getRelicId() != null
                        && !stock.getRelicId().equals(line.getRelicId())) {
                    Relic foundRelic = relicMapper.selectById(stock.getRelicId());
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, line,
                            REASON_LABEL_MISMATCH, "展签 " + label + " 对应文物 "
                                    + line.getRelicNo() + "，但箱号 " + box + " 内为文物 "
                                    + (foundRelic == null ? stock.getRelicId() : foundRelic.getRelicNo()));
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    fillFoundHall(diff, stock.getRelicId());
                    result.diffs.add(diff);
                    continue;
                }
                if (point != null && !point.trim().isEmpty()
                        && line.getPointCode() != null && !line.getPointCode().trim().isEmpty()
                        && !point.trim().equals(line.getPointCode().trim())) {
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, line,
                            REASON_WRONG_POINT, "应在点位 " + line.getPointCode()
                                    + "，实际扫描点位 " + point);
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    diff.setFoundPointCode(point);
                    result.diffs.add(diff);
                    continue;
                }
                if (stock == null) {
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, line,
                            REASON_UNKNOWN_BOX, "展签 " + label + " 在位但箱号 " + box
                                    + " 在当前库房库存中查不到");
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    diff.setFoundPointCode(point);
                    result.diffs.add(diff);
                    continue;
                }

                AbnormalCheck abnormal = checkAbnormal(line, scan);
                List<String> abnormalReasons = abnormal.reasons;
                if (!abnormalReasons.isEmpty()) {
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_STATUS_ABNORMAL, line,
                            abnormal.reasonCode, String.join("；", abnormalReasons));
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    diff.setFoundPointCode(point);
                    result.diffs.add(diff);
                }
                // 其余：在位且状态正常，无差异
                continue;
            }

            // 展签/箱对应本厅已撤展行：交由撤展扫描循环统一判状态异常，不在此重复出差异
            boolean withdrawnHit = withdrawn.stream().anyMatch(w ->
                    (w.getDisplayNo() != null
                            && w.getDisplayNo().trim().equals(label))
                    || (stockByBox.get(box) != null
                            && stockByBox.get(box).getRelicId() != null
                            && stockByBox.get(box).getRelicId().equals(w.getRelicId())));
            if (withdrawnHit) {
                continue;
            }

            // 展签不属于本厅：是否本展览其他厅的在陈文物
            HallRelic otherHr = otherHallLabel.get(label);
            if (otherHr != null) {
                Hall otherHall = hallMapper.selectById(otherHr.getHallId());
                Relic otherRelic = relicMapper.selectById(otherHr.getRelicId());
                StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, null,
                        REASON_WRONG_HALL, "展签 " + label + " 属于本展览其他展厅 "
                                + (otherHall == null ? otherHr.getHallId() : otherHall.getHallNo())
                                + " 的在陈文物 "
                                + (otherRelic == null ? otherHr.getRelicId() : otherRelic.getRelicNo()));
                diff.setRelicId(otherHr.getRelicId());
                if (otherRelic != null) {
                    diff.setRelicNo(otherRelic.getRelicNo());
                    diff.setRelicName(otherRelic.getName());
                }
                diff.setFoundBoxNo(box);
                diff.setFoundLabelNo(label);
                diff.setFoundPointCode(point);
                diff.setFoundHallId(otherHr.getHallId());
                if (otherHall != null) {
                    diff.setFoundHallNo(otherHall.getHallNo());
                }
                result.diffs.add(diff);
                continue;
            }

            // 展签未知：看箱中文物
            StorageStock stock = stockByBox.get(box);
            if (stock != null && stock.getRelicId() != null) {
                StocktakeBaselineLine ownLine = expected.stream()
                        .filter(l -> stock.getRelicId().equals(l.getRelicId()))
                        .findFirst().orElse(null);
                Relic boxRelic = relicMapper.selectById(stock.getRelicId());
                if (ownLine != null) {
                    matchedLineIds.add(ownLine.getId());
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, ownLine,
                            REASON_UNKNOWN_LABEL, "箱号 " + box + " 内为应在陈文物 "
                                    + (boxRelic == null ? stock.getRelicId() : boxRelic.getRelicNo())
                                    + "，但扫描展签 " + label + " 与登记展签 "
                                    + ownLine.getDisplayNo() + " 不符");
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    diff.setFoundPointCode(point);
                    result.diffs.add(diff);
                } else {
                    HallRelic relicHr = hallRelicMapper.selectOne(
                            new LambdaQueryWrapper<HallRelic>()
                                    .eq(HallRelic::getRelicId, stock.getRelicId())
                                    .eq(HallRelic::getStatus, DISPLAY_ON)
                                    .in(HallRelic::getHallId, hallIds)
                                    .last("LIMIT 1"));
                    StocktakeDiff diff = new StocktakeDiff();
                    diff.setTaskId(task.getId());
                    diff.setRoundId(round.getId());
                    diff.setDiffType(DIFF_MISPLACED);
                    diff.setRelicId(stock.getRelicId());
                    if (boxRelic != null) {
                        diff.setRelicNo(boxRelic.getRelicNo());
                        diff.setRelicName(boxRelic.getName());
                    }
                    diff.setFoundBoxNo(box);
                    diff.setFoundLabelNo(label);
                    diff.setFoundPointCode(point);
                    if (relicHr != null && !task.getHallId().equals(relicHr.getHallId())) {
                        Hall h = hallMapper.selectById(relicHr.getHallId());
                        diff.setReasonCode(REASON_WRONG_HALL);
                        diff.setReasonDetail("箱号 " + box + " 内文物 "
                                + (boxRelic == null ? stock.getRelicId() : boxRelic.getRelicNo())
                                + " 陈展于其他展厅 " + (h == null ? relicHr.getHallId() : h.getHallNo()));
                        diff.setFoundHallId(relicHr.getHallId());
                        if (h != null) {
                            diff.setFoundHallNo(h.getHallNo());
                        }
                    } else {
                        diff.setReasonCode(REASON_UNKNOWN_LABEL);
                        diff.setReasonDetail("箱号 " + box + " 内文物 "
                                + (boxRelic == null ? stock.getRelicId() : boxRelic.getRelicNo())
                                + " 不在本厅清点基准中，扫描展签 " + label + " 无法识别");
                    }
                    fillLiveDefaults(diff);
                    result.diffs.add(diff);
                }
            } else {
                // 箱签均未知：产生一条无文物归属的错位差异
                StocktakeDiff diff = newLiveDiff(round, task, DIFF_MISPLACED, null,
                        REASON_UNKNOWN_LABEL, "扫描展签 " + label + " 无法识别，且箱号 " + box
                                + " 在当前库房库存中查不到（UNKNOWN_BOX），来源待查");
                diff.setFoundBoxNo(box);
                diff.setFoundLabelNo(label);
                diff.setFoundPointCode(point);
                result.diffs.add(diff);
            }
        }

        // 已撤展但实物仍扫到
        for (StocktakeScanLine scan : uniqueScans.values()) {
            for (StocktakeBaselineLine w : withdrawn) {
                boolean labelHit = w.getDisplayNo() != null
                        && w.getDisplayNo().trim().equals(scan.getLabelNo().trim());
                StorageStock stock = stockByBox.get(scan.getBoxNo());
                boolean boxHit = stock != null && stock.getRelicId() != null
                        && stock.getRelicId().equals(w.getRelicId());
                if ((labelHit || boxHit) && !matchedLineIds.contains(w.getId())) {
                    matchedLineIds.add(w.getId());
                    StocktakeDiff diff = newLiveDiff(round, task, DIFF_STATUS_ABNORMAL, w,
                            REASON_WITHDRAWN_PRESENT, "该文物已办理撤展（陈展记录为 WITHDRAWN），"
                                    + "但现场仍扫到实物");
                    diff.setFoundBoxNo(scan.getBoxNo());
                    diff.setFoundLabelNo(scan.getLabelNo());
                    diff.setFoundPointCode(scan.getScanPointCode());
                    result.diffs.add(diff);
                }
            }
        }

        // 应在陈但从未匹配 → 缺失
        for (StocktakeBaselineLine line : expected) {
            if (!matchedLineIds.contains(line.getId())) {
                result.diffs.add(newLiveDiff(round, task, DIFF_MISSING, line,
                        REASON_NOT_SCANNED, "应在陈文物未扫到箱号/展签，判定缺失"));
            }
        }
        return result;
    }

    /** 在位文物的状态异常判定，按固定优先级确定原因码并拼接全部原因 */
    private AbnormalCheck checkAbnormal(StocktakeBaselineLine line, StocktakeScanLine scan) {
        List<String> reasons = new ArrayList<>();
        String reasonCode = null;
        if (REVOKED.equals(line.getRelicStatus())) {
            reasons.add("文物档案状态为已撤销（REVOKED），实物仍在展");
            reasonCode = REASON_REVOKED;
        }
        if (DISPLAY_WITHDRAWN.equals(line.getHallRelicStatus())) {
            reasons.add("陈展记录已撤展（WITHDRAWN），实物仍扫到");
            if (reasonCode == null) {
                reasonCode = REASON_WITHDRAWN_PRESENT;
            }
        }
        if (LOAN_LENT.equals(line.getLoanStatus())) {
            reasons.add("文物存在未还借出记录（" + line.getLoanNo() + "），实物仍在展");
            if (reasonCode == null) {
                reasonCode = REASON_ON_LOAN_PRESENT;
            }
        }
        if (line.getOpenRepairFlag() != null && line.getOpenRepairFlag() == 1) {
            reasons.add("文物存在未结修复项目（" + line.getOpenRepairNos() + "）");
            if (reasonCode == null) {
                reasonCode = REASON_IN_REPAIR;
            }
        }
        if (scan.getConditionAbnormal() != null && scan.getConditionAbnormal() == 1) {
            reasons.add("现场上报状态异常：" + scan.getNote());
            if (reasonCode == null) {
                reasonCode = REASON_DAMAGE_REPORTED;
            }
        }
        AbnormalCheck check = new AbnormalCheck();
        check.reasons = reasons;
        check.reasonCode = reasonCode;
        return check;
    }

    private static class AbnormalCheck {
        List<String> reasons = new ArrayList<>();
        String reasonCode;
    }

    private void fillFoundHall(StocktakeDiff diff, Long relicId) {
        HallRelic hr = hallRelicMapper.selectOne(new LambdaQueryWrapper<HallRelic>()
                .eq(HallRelic::getRelicId, relicId)
                .eq(HallRelic::getStatus, DISPLAY_ON)
                .last("LIMIT 1"));
        if (hr != null) {
            Hall hall = hallMapper.selectById(hr.getHallId());
            diff.setFoundHallId(hr.getHallId());
            if (hall != null) {
                diff.setFoundHallNo(hall.getHallNo());
            }
        }
    }

    // ==================================================================
    // 复核 / 解释 / 确认 / 关闭 / 补充
    // ==================================================================

    /** 任务复核：差异解释（LIVE 还须照片+双见证人）闸门 */
    public StocktakeTask reviewTask(Long taskId, StocktakeReviewRequest req) {
        StocktakeTask task = requireTask(taskId);
        StocktakeRound round = requireRound(task.getRoundId());
        LocalDateTime now = LocalDateTime.now();
        if (ST_TASK_REVIEWED.equals(task.getStatus()) || ST_TASK_CLOSED.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo() + " 已"
                    + (ST_TASK_CLOSED.equals(task.getStatus()) ? "随版本关闭" : "复核")
                    + "，不能重复复核");
        }
        if (!ST_TASK_SUBMITTED.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                    + " 尚未提交，不能复核");
        }
        List<StocktakeDiff> diffs = diffsOfTask(taskId);
        for (StocktakeDiff diff : diffs) {
            if (diff.getExplanation() == null || diff.getExplanation().trim().isEmpty()) {
                throw new IllegalArgumentException("差异 #" + diff.getDiffNo() + " 尚无解释，"
                        + "所有差异必须能解释后才能复核");
            }
            if (diff.getPhotoRequired() != null && diff.getPhotoRequired() == 1) {
                long photoCount = diffPhotoMapper.selectCount(
                        new LambdaQueryWrapper<StocktakeDiffPhoto>()
                                .eq(StocktakeDiffPhoto::getDiffId, diff.getId()));
                if (photoCount <= 0) {
                    throw new IllegalArgumentException("现场差异 #" + diff.getDiffNo()
                            + " 未关联现场照片，至少需要一张，不能复核");
                }
                if (blank(diff.getWitnessA()) || blank(diff.getWitnessB())) {
                    throw new IllegalArgumentException("现场差异 #" + diff.getDiffNo()
                            + " 缺少两名见证人，不能复核");
                }
                if (diff.getWitnessA().trim().equals(diff.getWitnessB().trim())) {
                    throw new IllegalArgumentException("现场差异 #" + diff.getDiffNo()
                            + " 的两名见证人不能为同一人，不能复核");
                }
            }
        }
        task.setStatus(ST_TASK_REVIEWED);
        task.setReviewedBy(req.getReviewedBy().trim());
        task.setReviewedAt(now);
        task.setReviewRemark(req.getRemark());
        taskMapper.updateById(task);
        ledgerService.append(round.getExhibitionId(), round.getId(), taskId, task.getHallId(),
                ACTION_ST_REVIEW, task.getTaskNo(),
                "展厅 " + task.getHallNo() + " 清点任务复核通过（差异 " + diffs.size()
                        + " 条全部有解释），复核人 " + req.getReviewedBy(), now);
        return task;
    }

    /** 差异解释：人工差异复核前必填；LIVE 须先有照片+两名不同见证人 */
    public StocktakeDiff explainDiff(Long diffId, DiffExplainRequest req) {
        StocktakeDiff diff = requireDiff(diffId);
        StocktakeTask task = requireTask(diff.getTaskId());
        StocktakeRound round = requireRound(diff.getRoundId());
        if (!ST_TASK_SUBMITTED.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo() + " 当前状态为 "
                    + task.getStatus() + "，只能在待复核期补充差异解释");
        }
        if (ROUND_REPLAY.equals(round.getKind())
                && ((req.getWitnessA() != null && !req.getWitnessA().trim().isEmpty())
                || (req.getWitnessB() != null && !req.getWitnessB().trim().isEmpty()))) {
            throw new IllegalArgumentException("回放差异由台账自动解释，无需登记见证人");
        }
        if (diff.getPhotoRequired() != null && diff.getPhotoRequired() == 1) {
            long photoCount = diffPhotoMapper.selectCount(
                    new LambdaQueryWrapper<StocktakeDiffPhoto>()
                            .eq(StocktakeDiffPhoto::getDiffId, diffId));
            if (photoCount <= 0) {
                throw new IllegalArgumentException("差异 #" + diff.getDiffNo()
                        + " 尚未上传现场照片，至少需要一张后才能登记解释");
            }
            if (blank(req.getWitnessA()) || blank(req.getWitnessB())) {
                throw new IllegalArgumentException("差异 #" + diff.getDiffNo()
                        + " 必须填写两名见证人后才能登记解释");
            }
            if (req.getWitnessA().trim().equals(req.getWitnessB().trim())) {
                throw new IllegalArgumentException("差异 #" + diff.getDiffNo()
                        + " 的两名见证人不能为同一人");
            }
            diff.setWitnessA(req.getWitnessA().trim());
            diff.setWitnessB(req.getWitnessB().trim());
        }
        diff.setExplanation(req.getExplanation().trim());
        diff.setExplanationSource(EXPLAIN_MANUAL);
        diff.setAutoExplained(0);
        diff.setExplainedBy(req.getExplainedBy().trim());
        diff.setExplainedAt(LocalDateTime.now());
        diffMapper.updateById(diff);
        ledgerService.append(round.getExhibitionId(), round.getId(), task.getId(),
                task.getHallId(), ACTION_ST_DIFF_EXPLAIN, task.getTaskNo() + "#" + diff.getDiffNo(),
                "差异 #" + diff.getDiffNo() + "（" + diff.getDiffType() + "/"
                        + diff.getReasonCode() + "）已由 " + req.getExplainedBy() + " 登记解释",
                LocalDateTime.now());
        return diff;
    }

    /** 差异逐条确认 */
    public StocktakeDiff confirmDiff(Long diffId, DiffConfirmRequest req) {
        StocktakeDiff diff = requireDiff(diffId);
        StocktakeTask task = requireTask(diff.getTaskId());
        StocktakeRound round = requireRound(diff.getRoundId());
        if (!ST_TASK_SUBMITTED.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo() + " 当前状态为 "
                    + task.getStatus() + "，只能在待复核期确认差异");
        }
        if (diff.getExplanation() == null || diff.getExplanation().trim().isEmpty()) {
            throw new IllegalArgumentException("差异 #" + diff.getDiffNo()
                    + " 尚无解释，不能确认");
        }
        diff.setConfirmStatus(CONFIRM_CONFIRMED);
        diff.setConfirmedBy(req.getConfirmedBy().trim());
        diff.setConfirmedAt(LocalDateTime.now());
        diffMapper.updateById(diff);
        ledgerService.append(round.getExhibitionId(), round.getId(), task.getId(),
                task.getHallId(), ACTION_ST_DIFF_CONFIRM, task.getTaskNo() + "#" + diff.getDiffNo(),
                "差异 #" + diff.getDiffNo() + " 由 " + req.getConfirmedBy() + " 确认",
                LocalDateTime.now());
        return diff;
    }

    /** 关闭清点版本：全部任务复核 + 全部差异确认 */
    public StocktakeRound closeRound(Long roundId, String closedBy, String remark) {
        String operator = closedBy.trim();
        try {
            return transactionTemplate.execute(status -> {
                roundMapper.lockRow(roundId);
                StocktakeRound round = requireRound(roundId);
                if (ROUND_CLOSED.equals(round.getStatus())) {
                    throw new IllegalArgumentException("清点版本 " + round.getRoundNo()
                            + " 已关闭，不能重复关闭");
                }
                if (ROUND_SUPERSEDED.equals(round.getStatus())) {
                    throw new IllegalArgumentException("清点版本 " + round.getRoundNo()
                            + " 已被新版本取代，不能关闭");
                }
                List<StocktakeTask> tasks = tasksOfRound(roundId);
                for (StocktakeTask task : tasks) {
                    if (!ST_TASK_REVIEWED.equals(task.getStatus())) {
                        throw new IllegalArgumentException("展厅 " + task.getHallNo()
                                + " 的清点任务尚未复核完成，不能关闭清点版本");
                    }
                }
                long pending = diffMapper.selectCount(new LambdaQueryWrapper<StocktakeDiff>()
                        .eq(StocktakeDiff::getRoundId, roundId)
                        .eq(StocktakeDiff::getConfirmStatus, CONFIRM_PENDING));
                if (pending > 0) {
                    throw new IllegalArgumentException("仍有 " + pending
                            + " 条差异未确认，全部确认后才能关闭清点版本");
                }
                LocalDateTime now = LocalDateTime.now();
                round.setStatus(ROUND_CLOSED);
                round.setClosedBy(operator);
                round.setClosedAt(now);
                round.setCloseRemark(remark);
                roundMapper.updateById(round);
                for (StocktakeTask task : tasks) {
                    task.setStatus(ST_TASK_CLOSED);
                    taskMapper.updateById(task);
                }
                insertAttempt(round, null, ST_OP_CLOSE_ROUND, null, operator, round.getId(), now);
                ledgerService.append(round.getExhibitionId(), roundId, null, null,
                        ACTION_ST_ROUND_CLOSE, round.getRoundNo(),
                        "清点版本 " + round.getRoundNo() + "（" + tasks.size()
                                + " 个展厅任务全部复核、差异全部确认）关闭，关闭人 " + operator, now);
                return round;
            });
        } catch (IllegalArgumentException e) {
            auditService.recordRejected(roundId, null, ST_OP_CLOSE_ROUND, null,
                    operator, e.getMessage(),
                    roundMapper.selectById(roundId) == null ? null
                            : roundMapper.selectById(roundId).getExhibitionId(), null);
            throw e;
        }
    }

    /** 关闭后追加补充记录（原结果不可变） */
    @Transactional
    public StocktakeSupplement addSupplement(Long roundId, SupplementRequest req) {
        StocktakeRound round = requireRound(roundId);
        if (!ROUND_CLOSED.equals(round.getStatus())) {
            throw new IllegalArgumentException("清点版本 " + round.getRoundNo()
                    + " 尚未关闭；版本关闭后清点结果不可改，只能通过补充记录追加说明");
        }
        if (!blank(req.getWitnessA()) && !blank(req.getWitnessB())
                && req.getWitnessA().trim().equals(req.getWitnessB().trim())) {
            throw new IllegalArgumentException("补充记录的两名见证人不能为同一人");
        }
        if (req.getTaskId() != null) {
            StocktakeTask task = requireTask(req.getTaskId());
            if (!task.getRoundId().equals(roundId)) {
                throw new IllegalArgumentException("补充任务不属于该清点版本");
            }
        }
        if (req.getDiffId() != null) {
            StocktakeDiff diff = requireDiff(req.getDiffId());
            if (!diff.getRoundId().equals(roundId)) {
                throw new IllegalArgumentException("补充差异不属于该清点版本");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        StocktakeSupplement supplement = new StocktakeSupplement();
        supplement.setRoundId(roundId);
        supplement.setTaskId(req.getTaskId());
        supplement.setDiffId(req.getDiffId());
        supplement.setContent(req.getContent().trim());
        supplement.setSubmittedBy(req.getSubmittedBy().trim());
        supplement.setSubmittedAt(now);
        supplement.setWitnessA(trimToNull(req.getWitnessA()));
        supplement.setWitnessB(trimToNull(req.getWitnessB()));
        supplementMapper.insert(supplement);
        insertAttempt(round, null, ST_OP_SUPPLEMENT, null,
                req.getSubmittedBy(), supplement.getId(), now);
        ledgerService.append(round.getExhibitionId(), roundId, req.getTaskId(), null,
                ACTION_ST_SUPPLEMENT, String.valueOf(supplement.getId()),
                "清点版本关闭后追加补充记录#" + supplement.getId() + "，提交人 "
                        + req.getSubmittedBy()
                        + (req.getDiffId() == null ? "" : "（针对差异 #" + req.getDiffId() + "）"), now);
        return supplement;
    }

    /** 差异现场照片：内容寻址复用影像库，同差异同 hash 不重复 */
    @Transactional
    public StocktakeDiffPhoto uploadDiffPhoto(Long diffId, MultipartFile file,
                                              String uploadedBy, String remark) {
        if (blank(uploadedBy)) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("现场照片不能为空");
        }
        StocktakeDiff diff = requireDiff(diffId);
        StocktakeTask task = requireTask(diff.getTaskId());
        if (!ST_TASK_SUBMITTED.equals(task.getStatus())) {
            throw new IllegalArgumentException("清点任务 " + task.getTaskNo()
                    + " 当前状态为 " + task.getStatus() + "，现场照片只能在待复核期关联");
        }
        ImageStorage.StoredPhoto stored;
        try {
            stored = imageStorage.store(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("现场照片读取失败");
        }
        PhotoFile photoFile = obtainPhotoFile(stored, file.getOriginalFilename(), uploadedBy);
        StocktakeDiffPhoto exists = diffPhotoMapper.selectOne(
                new LambdaQueryWrapper<StocktakeDiffPhoto>()
                        .eq(StocktakeDiffPhoto::getDiffId, diffId)
                        .eq(StocktakeDiffPhoto::getContentHash, stored.hash));
        if (exists != null) {
            throw new IllegalArgumentException("该现场照片已关联差异 #" + diff.getDiffNo()
                    + "（内容一致）");
        }
        StocktakeDiffPhoto photo = new StocktakeDiffPhoto();
        photo.setDiffId(diffId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setUploadedBy(uploadedBy.trim());
        photo.setRemark(remark);
        diffPhotoMapper.insert(photo);
        return photo;
    }

    /** 补充记录照片 */
    @Transactional
    public StocktakeSupplementPhoto uploadSupplementPhoto(Long roundId, Long supplementId,
                                                          MultipartFile file,
                                                          String uploadedBy, String remark) {
        if (blank(uploadedBy)) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("现场照片不能为空");
        }
        StocktakeSupplement supplement = supplementMapper.selectById(supplementId);
        if (supplement == null || !supplement.getRoundId().equals(roundId)) {
            throw new IllegalArgumentException("补充记录不存在或不属于该清点版本");
        }
        ImageStorage.StoredPhoto stored;
        try {
            stored = imageStorage.store(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("现场照片读取失败");
        }
        PhotoFile photoFile = obtainPhotoFile(stored, file.getOriginalFilename(), uploadedBy);
        StocktakeSupplementPhoto exists = supplementPhotoMapper.selectOne(
                new LambdaQueryWrapper<StocktakeSupplementPhoto>()
                        .eq(StocktakeSupplementPhoto::getSupplementId, supplementId)
                        .eq(StocktakeSupplementPhoto::getContentHash, stored.hash));
        if (exists != null) {
            throw new IllegalArgumentException("该照片已关联补充记录 #" + supplementId
                    + "（内容一致）");
        }
        StocktakeSupplementPhoto photo = new StocktakeSupplementPhoto();
        photo.setSupplementId(supplementId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(stored.hash);
        photo.setPhotoName(stored.fileName);
        photo.setUploadedBy(uploadedBy.trim());
        photo.setRemark(remark);
        supplementPhotoMapper.insert(photo);
        return photo;
    }

    private PhotoFile obtainPhotoFile(ImageStorage.StoredPhoto stored, String fileName,
                                      String uploadedBy) {
        PhotoFile existing = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                .eq(PhotoFile::getContentHash, stored.hash));
        if (existing != null) {
            existing.setRefCount(existing.getRefCount() + 1);
            photoFileMapper.updateById(existing);
            return existing;
        }
        PhotoFile photoFile = new PhotoFile();
        photoFile.setContentHash(stored.hash);
        photoFile.setStoragePath(stored.storagePath);
        photoFile.setFileName(stored.fileName);
        photoFile.setContentType(stored.contentType);
        photoFile.setFileSize(stored.size);
        photoFile.setWidthPx(stored.width);
        photoFile.setHeightPx(stored.height);
        photoFile.setShotAt(LocalDateTime.now());
        photoFile.setPhotographer(uploadedBy.trim());
        photoFile.setRefCount(1);
        try {
            photoFileMapper.insert(photoFile);
            return photoFile;
        } catch (DuplicateKeyException dup) {
            PhotoFile raced = photoFileMapper.selectOne(new LambdaQueryWrapper<PhotoFile>()
                    .eq(PhotoFile::getContentHash, stored.hash));
            raced.setRefCount(raced.getRefCount() + 1);
            photoFileMapper.updateById(raced);
            return raced;
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public List<StocktakeTask> listTasks(Long roundId, Long hallId, String status) {
        return taskMapper.selectList(new LambdaQueryWrapper<StocktakeTask>()
                .eq(roundId != null, StocktakeTask::getRoundId, roundId)
                .eq(hallId != null, StocktakeTask::getHallId, hallId)
                .eq(status != null && !status.isEmpty(), StocktakeTask::getStatus, status)
                .orderByAsc(StocktakeTask::getId));
    }

    public Map<String, Object> taskDetail(Long taskId) {
        StocktakeTask task = requireTask(taskId);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("task", task);
        bundle.put("round", requireRound(task.getRoundId()));
        bundle.put("baselineLines", baselineMapper.selectList(
                new LambdaQueryWrapper<StocktakeBaselineLine>()
                        .eq(StocktakeBaselineLine::getTaskId, taskId)
                        .orderByAsc(StocktakeBaselineLine::getLineNo)));
        List<StocktakeScanBatch> batches = batchMapper.selectList(
                new LambdaQueryWrapper<StocktakeScanBatch>()
                        .eq(StocktakeScanBatch::getTaskId, taskId)
                        .orderByAsc(StocktakeScanBatch::getId));
        List<Map<String, Object>> batchBundle = new ArrayList<>();
        for (StocktakeScanBatch batch : batches) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("batch", batch);
            b.put("lines", scanLineMapper.selectList(new LambdaQueryWrapper<StocktakeScanLine>()
                    .eq(StocktakeScanLine::getBatchId, batch.getId())
                    .orderByAsc(StocktakeScanLine::getSeqNo)));
            batchBundle.add(b);
        }
        bundle.put("batches", batchBundle);
        List<StocktakeDiff> diffs = diffsOfTask(taskId);
        List<Map<String, Object>> diffBundle = new ArrayList<>();
        for (StocktakeDiff diff : diffs) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("diff", diff);
            d.put("photos", diffPhotoMapper.selectList(new LambdaQueryWrapper<StocktakeDiffPhoto>()
                    .eq(StocktakeDiffPhoto::getDiffId, diff.getId())
                    .orderByAsc(StocktakeDiffPhoto::getId)));
            diffBundle.add(d);
        }
        bundle.put("diffs", diffBundle);
        bundle.put("supplements", supplementsOfRound(task.getRoundId(), taskId));
        return bundle;
    }

    public Map<String, Object> roundDetail(Long roundId) {
        StocktakeRound round = requireRound(roundId);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("round", round);
        bundle.put("exhibition", exhibitionMapper.selectById(round.getExhibitionId()));
        bundle.put("tasks", taskMapper.selectList(new LambdaQueryWrapper<StocktakeTask>()
                .eq(StocktakeTask::getRoundId, roundId)
                .orderByAsc(StocktakeTask::getHallId)));
        bundle.put("supplements", supplementsOfRound(roundId, null));
        return bundle;
    }

    public List<StocktakeRound> roundsOfExhibition(Long exhibitionId) {
        return roundMapper.selectList(new LambdaQueryWrapper<StocktakeRound>()
                .eq(StocktakeRound::getExhibitionId, exhibitionId)
                .orderByAsc(StocktakeRound::getVersionNo));
    }

    /** 版本冲突预检：返回当前基准与冻结基准的差异清单 */
    public Map<String, Object> baselineChanges(Long taskId) {
        StocktakeTask task = requireTask(taskId);
        List<BaselineChange> changes = detectChanges(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", !changes.isEmpty());
        result.put("baselineFingerprint", task.getBaselineFingerprint());
        BaselineSnapshot current = buildBaseline(task.getHallId());
        result.put("currentFingerprint", current.fingerprint);
        result.put("changeCount", changes.size());
        result.put("changes", changes);
        return result;
    }

    public List<StocktakeAttempt> listAttempts(Long roundId, Long taskId, String operation) {
        return attemptMapper.selectList(new LambdaQueryWrapper<StocktakeAttempt>()
                .eq(roundId != null, StocktakeAttempt::getRoundId, roundId)
                .eq(taskId != null, StocktakeAttempt::getTaskId, taskId)
                .eq(operation != null && !operation.isEmpty(),
                        StocktakeAttempt::getOperation, operation)
                .orderByAsc(StocktakeAttempt::getId));
    }

    // ==================================================================
    // 基准构建 / 指纹 / 版本冲突检测
    // ==================================================================

    static class BaselineSnapshot {
        List<StocktakeBaselineLine> lines = new ArrayList<>();
        String fingerprint;
        Hall hall;
    }

    static class BaselineChange {
        Long relicId;
        String relicNo;
        String changeType;
        String fromValue;
        String toValue;
        String action;
        String bizNo;
        LocalDateTime occurredAt;
        String summary;

        public Long getRelicId() { return relicId; }
        public String getRelicNo() { return relicNo; }
        public String getChangeType() { return changeType; }
        public String getFromValue() { return fromValue; }
        public String getToValue() { return toValue; }
        public String getAction() { return action; }
        public String getBizNo() { return bizNo; }
        public LocalDateTime getOccurredAt() { return occurredAt; }
        public String getSummary() { return summary; }
    }

    /** 按展厅重算当前基准（冻结与冲突检测共用同一套查询） */
    private BaselineSnapshot buildBaseline(Long hallId) {
        Hall hall = hallMapper.selectById(hallId);
        if (hall == null) {
            throw new IllegalArgumentException("展厅不存在: hallId=" + hallId);
        }
        BaselineSnapshot snapshot = new BaselineSnapshot();
        snapshot.hall = hall;
        List<HallRelic> hallRelics = hallRelicMapper.selectList(
                new LambdaQueryWrapper<HallRelic>()
                        .eq(HallRelic::getHallId, hallId)
                        .orderByAsc(HallRelic::getId));
        StringBuilder canonical = new StringBuilder();
        canonical.append("H|").append(hall.getId()).append('|').append(nvl(hall.getStatus()))
                .append('|').append(hallRelics.size());
        int lineNo = 0;
        for (HallRelic hr : hallRelics) {
            StocktakeBaselineLine line = new StocktakeBaselineLine();
            line.setLineNo(++lineNo);
            line.setHallRelicId(hr.getId());
            line.setRelicId(hr.getRelicId());
            line.setPointCode(hr.getPointCode());
            line.setDisplayNo(hr.getDisplayNo());
            line.setHallRelicStatus(hr.getStatus());
            line.setHallStatus(hall.getStatus());
            line.setExpected(DISPLAY_ON.equals(hr.getStatus()) ? 1 : 0);
            line.setMaterial(hr.getMaterial());

            Relic relic = relicMapper.selectById(hr.getRelicId());
            if (relic != null) {
                line.setRelicNo(relic.getRelicNo());
                line.setRelicName(relic.getName());
                line.setRelicStatus(relic.getStatus());
            }
            RelicLoan openLoan = relicLoanMapper.selectOne(new LambdaQueryWrapper<RelicLoan>()
                    .eq(RelicLoan::getRelicId, hr.getRelicId())
                    .eq(RelicLoan::getStatus, LOAN_LENT)
                    .orderByDesc(RelicLoan::getId)
                    .last("LIMIT 1"));
            if (openLoan != null) {
                line.setLoanNo(openLoan.getLoanNo());
                line.setLoanStatus(LOAN_LENT);
            }
            List<RepairProject> openRepairs = repairProjectMapper.selectList(
                    new LambdaQueryWrapper<RepairProject>()
                            .eq(RepairProject::getRelicId, hr.getRelicId())
                            .in(RepairProject::getStatus,
                                    PROJECT_PLANNED, PROJECT_REPAIRING, PROJECT_REPAIRED)
                            .orderByAsc(RepairProject::getId));
            if (!openRepairs.isEmpty()) {
                line.setOpenRepairFlag(1);
                line.setOpenRepairNos(openRepairs.stream()
                        .map(RepairProject::getProjectNo).collect(Collectors.joining(",")));
            } else {
                line.setOpenRepairFlag(0);
            }
            List<StorageStock> boxes = storageStockMapper.selectList(
                    new LambdaQueryWrapper<StorageStock>()
                            .eq(StorageStock::getRelicId, hr.getRelicId())
                            .orderByAsc(StorageStock::getBoxNo));
            if (!boxes.isEmpty()) {
                line.setBoxNo(boxes.stream().map(StorageStock::getBoxNo)
                        .collect(Collectors.joining(",")));
            }
            snapshot.lines.add(line);

            canonical.append('\n')
                    .append(nvl(String.valueOf(hr.getRelicId()))).append('|')
                    .append(nvl(String.valueOf(hr.getId()))).append('|')
                    .append(nvl(hr.getStatus())).append('|')
                    .append(nvl(hr.getPointCode())).append('|')
                    .append(nvl(hr.getDisplayNo())).append('|')
                    .append(relic == null ? "" : nvl(relic.getStatus())).append('|')
                    .append(openLoan == null ? "" : nvl(openLoan.getLoanNo())).append('|')
                    .append(nvl(line.getOpenRepairNos())).append('|')
                    .append(nvl(line.getBoxNo()));
        }
        snapshot.fingerprint = sha256(canonical.toString());
        return snapshot;
    }

    /** 重算基准并与冻结行逐项 diff，附台账佐证 */
    private List<BaselineChange> detectChanges(StocktakeTask task) {
        BaselineSnapshot current = buildBaseline(task.getHallId());
        List<StocktakeBaselineLine> frozen = baselineLines(task.getId());
        Map<Long, StocktakeBaselineLine> frozenByHr = new HashMap<>();
        for (StocktakeBaselineLine l : frozen) {
            frozenByHr.put(l.getHallRelicId(), l);
        }
        List<BaselineChange> changes = new ArrayList<>();

        if (!nvl(task.getHallStatus()).equals(nvl(current.hall.getStatus()))) {
            changes.add(change(null, null, "HALL_STATUS",
                    task.getHallStatus(), current.hall.getStatus(), null,
                    "展厅状态由 " + task.getHallStatus() + " 变为 " + current.hall.getStatus()));
        }

        for (StocktakeBaselineLine now : current.lines) {
            StocktakeBaselineLine old = frozenByHr.get(now.getHallRelicId());
            if (old == null) {
                BaselineChange c = change(now.getRelicId(), now.getRelicNo(), "NEW_DISPLAY",
                        null, now.getHallRelicStatus() + "(" + nvl(now.getDisplayNo()) + ")",
                        now.getRelicId(),
                        "清点期间新增陈展记录：文物 " + now.getRelicNo() + " 展签 "
                                + nvl(now.getDisplayNo()) + " 点位 " + nvl(now.getPointCode()));
                changes.add(c);
                continue;
            }
            if (!nvl(old.getHallRelicStatus()).equals(nvl(now.getHallRelicStatus()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "HALL_RELIC_STATUS",
                        old.getHallRelicStatus(), now.getHallRelicStatus(), task.getFrozenAt(),
                        "陈展记录状态 " + old.getHallRelicStatus() + "→" + now.getHallRelicStatus()
                                + "（展签 " + nvl(now.getDisplayNo()) + "）"));
            }
            if (!nvl(old.getPointCode()).equals(nvl(now.getPointCode()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "POINT_CHANGE",
                        old.getPointCode(), now.getPointCode(), task.getFrozenAt(),
                        "点位 " + nvl(old.getPointCode()) + "→" + nvl(now.getPointCode())));
            }
            if (!nvl(old.getDisplayNo()).equals(nvl(now.getDisplayNo()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "LABEL_CHANGE",
                        old.getDisplayNo(), now.getDisplayNo(), task.getFrozenAt(),
                        "展签 " + nvl(old.getDisplayNo()) + "→" + nvl(now.getDisplayNo())));
            }
            if (!nvl(old.getRelicStatus()).equals(nvl(now.getRelicStatus()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "RELIC_STATUS",
                        old.getRelicStatus(), now.getRelicStatus(), task.getFrozenAt(),
                        "文物状态 " + nvl(old.getRelicStatus()) + "→" + nvl(now.getRelicStatus())));
            }
            String oldLoan = old.getLoanStatus() == null ? "" : nvl(old.getLoanNo());
            String newLoan = now.getLoanStatus() == null ? "" : nvl(now.getLoanNo());
            if (!oldLoan.equals(newLoan)) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "LOAN_CHANGE",
                        old.getLoanStatus() == null ? "在馆" : old.getLoanStatus() + " " + old.getLoanNo(),
                        now.getLoanStatus() == null ? "在馆" : now.getLoanStatus() + " " + now.getLoanNo(),
                        task.getFrozenAt(),
                        "未还借出差集变化：" + oldLoan + " → " + newLoan));
            }
            if (!nvl(old.getOpenRepairNos()).equals(nvl(now.getOpenRepairNos()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "REPAIR_CHANGE",
                        nvl(old.getOpenRepairNos()), nvl(now.getOpenRepairNos()),
                        task.getFrozenAt(),
                        "未结修复项目变化：" + nvl(old.getOpenRepairNos()) + " → "
                                + nvl(now.getOpenRepairNos())));
            }
            if (!nvl(old.getBoxNo()).equals(nvl(now.getBoxNo()))) {
                changes.add(withEvidence(now.getRelicId(), now.getRelicNo(), "BOX_CHANGE",
                        nvl(old.getBoxNo()), nvl(now.getBoxNo()), task.getFrozenAt(),
                        "在库箱号变化：" + nvl(old.getBoxNo()) + " → " + nvl(now.getBoxNo())));
            }
        }

        // 指纹不一致但逐项未列出（理论上不会发生）时兜底报一条
        if (changes.isEmpty() && !task.getBaselineFingerprint().equals(current.fingerprint)) {
            changes.add(change(null, null, "BASELINE_CHANGED",
                    task.getBaselineFingerprint(), current.fingerprint, null,
                    "清点基准指纹发生变化，请重建清点版本"));
        }
        return changes;
    }

    private BaselineChange withEvidence(Long relicId, String relicNo, String type,
                                        String from, String to, LocalDateTime after,
                                        String summary) {
        BaselineChange c = change(relicId, relicNo, type, from, to, relicId, summary);
        if (relicId != null) {
            LedgerEntry entry = latestLedgerAfter(relicId, after);
            if (entry != null) {
                c.action = entry.getAction();
                c.bizNo = entry.getBizNo();
                c.occurredAt = entry.getOccurredAt();
                c.summary = summary + "；台账依据：" + entry.getAction() + " "
                        + nvl(entry.getBizNo()) + " " + entry.getOccurredAt();
            }
        }
        return c;
    }

    private LedgerEntry latestLedgerAfter(Long relicId, LocalDateTime after) {
        List<LedgerEntry> entries = relicLedgerService.listByRelic(relicId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            LedgerEntry e = entries.get(i);
            if (after != null && e.getOccurredAt() != null && e.getOccurredAt().isAfter(after)) {
                return e;
            }
        }
        return null;
    }

    private static class LedgerEvidence {
        LedgerEntry entry;
        LedgerEvidence(LedgerEntry entry) { this.entry = entry; }
    }

    private LedgerEvidence latestLendingEvidence(Long relicId, String loanNo, LocalDateTime before) {
        List<String> actions = Arrays.asList(ACTION_LOAN_OUT, ACTION_BORROW_LEND,
                ACTION_ESCORT_NODE);
        return pickLedger(relicId, actions, loanNo);
    }

    private LedgerEvidence latestRepairEvidence(Long relicId, String projectNos,
                                                LocalDateTime before) {
        Set<String> nos = splitCsv(projectNos);
        List<LedgerEntry> entries = relicLedgerService.listByRelic(relicId);
        for (int i = entries.size() - 1; i >= 0; i--) {
            LedgerEntry e = entries.get(i);
            if (ACTION_REPAIR_START.equals(e.getAction())
                    && (e.getBizNo() == null || nos.isEmpty() || nos.contains(e.getBizNo()))) {
                return new LedgerEvidence(e);
            }
        }
        return null;
    }

    private LedgerEvidence latestAction(Long relicId, String action, String bizNo,
                                        LocalDateTime before) {
        return pickLedger(relicId, Collections.singletonList(action), bizNo);
    }

    private LedgerEvidence pickLedger(Long relicId, List<String> actions, String bizNo) {
        List<LedgerEntry> entries = relicLedgerService.listByRelic(relicId);
        LedgerEntry fallback = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            LedgerEntry e = entries.get(i);
            if (!actions.contains(e.getAction())) {
                continue;
            }
            if (bizNo != null && bizNo.equals(e.getBizNo())) {
                return new LedgerEvidence(e);
            }
            if (fallback == null) {
                fallback = e;
            }
        }
        return fallback == null ? null : new LedgerEvidence(fallback);
    }

    private String conflictMessage(List<BaselineChange> changes) {
        String preview = changes.stream().limit(3)
                .map(c -> "[" + c.changeType + "] " + (c.relicNo == null ? "" : c.relicNo + " ")
                        + c.summary)
                .collect(Collectors.joining("；"));
        return "清点基准已发生版本冲突：共 " + changes.size() + " 项变更（" + preview
                + "），请先核对处理基准变更或基于当前版本重建清点版本";
    }

    // ==================================================================
    // 冻结 / rebase / 差异构造 / 回放辅助
    // ==================================================================

    private List<StocktakeTask> freezeTasks(StocktakeRound round, List<Long> hallIds,
                                            LocalDateTime now) {
        List<StocktakeTask> tasks = new ArrayList<>();
        for (Long hallId : hallIds.stream().sorted().collect(Collectors.toList())) {
            Hall hall = hallMapper.selectById(hallId);
            if (hall == null) {
                throw new IllegalArgumentException("展厅不存在: hallId=" + hallId);
            }
            if (!HALL_ACTIVE.equals(hall.getStatus())) {
                throw new IllegalArgumentException("展厅 " + hall.getHallNo()
                        + " 已关闭，不能生成清点任务");
            }
            BaselineSnapshot snapshot = buildBaseline(hallId);
            StocktakeTask task = new StocktakeTask();
            task.setRoundId(round.getId());
            task.setExhibitionId(round.getExhibitionId());
            task.setHallId(hallId);
            task.setHallNo(hall.getHallNo());
            task.setHallName(hall.getName());
            task.setHallStatus(hall.getStatus());
            task.setStatus(ST_TASK_PENDING);
            task.setBaselineFingerprint(snapshot.fingerprint);
            task.setFrozenAt(now);
            task.setTaskNo(String.format("ST-%06d", taskMapper.nextTaskSeq()));
            taskMapper.insert(task);
            int lineNo = 0;
            int expectedCount = 0;
            for (StocktakeBaselineLine line : snapshot.lines) {
                line.setId(null);
                line.setTaskId(task.getId());
                line.setLineNo(++lineNo);
                baselineMapper.insert(line);
                if (line.getExpected() != null && line.getExpected() == 1) {
                    expectedCount++;
                }
            }
            task.setBaselineCount(expectedCount);
            task.setScannedCount(0);
            task.setMissingCount(0);
            task.setMisplacedCount(0);
            task.setAbnormalCount(0);
            taskMapper.updateById(task);
            tasks.add(task);
        }
        return tasks;
    }

    private String aggregateFingerprint(List<StocktakeTask> tasks) {
        List<StocktakeTask> sorted = tasks.stream()
                .sorted(Comparator.comparing(StocktakeTask::getHallId))
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder("R");
        for (StocktakeTask t : sorted) {
            sb.append('\n').append(t.getHallId()).append('|').append(t.getBaselineFingerprint());
        }
        return sha256(sb.toString());
    }

    /** rebase：旧版本置 SUPERSEDED，已接受扫描批次搬运到新版本对应展厅任务 */
    private void carryScans(StocktakeRound source, StocktakeRound target,
                            List<StocktakeTask> newTasks, LocalDateTime now) {
        List<StocktakeTask> oldTasks = tasksOfRound(source.getId());
        Map<Long, StocktakeTask> newByHall = newTasks.stream()
                .collect(Collectors.toMap(StocktakeTask::getHallId, t -> t));
        for (StocktakeTask oldTask : oldTasks) {
            StocktakeTask newTask = newByHall.get(oldTask.getHallId());
            if (newTask == null) {
                continue;
            }
            List<StocktakeScanBatch> batches = batchMapper.selectList(
                    new LambdaQueryWrapper<StocktakeScanBatch>()
                            .eq(StocktakeScanBatch::getTaskId, oldTask.getId())
                            .orderByAsc(StocktakeScanBatch::getId));
            for (StocktakeBatchCarry carry : carryBatches(batches)) {
                StocktakeScanBatch oldBatch = carry.batch;
                List<StocktakeScanLine> oldLines = carry.lines;
                StocktakeScanBatch newBatch = new StocktakeScanBatch();
                newBatch.setTaskId(newTask.getId());
                newBatch.setRequestNo("CARRIED-" + oldBatch.getId());
                newBatch.setScannedBy(oldBatch.getScannedBy());
                newBatch.setScannedAt(oldBatch.getScannedAt());
                newBatch.setScanPointCode(oldBatch.getScanPointCode());
                newBatch.setRemark(oldBatch.getRemark());
                newBatch.setCarriedFromBatchId(oldBatch.getId());
                batchMapper.insert(newBatch);
                int seq = 0;
                for (StocktakeScanLine oldLine : oldLines) {
                    StocktakeScanLine nl = new StocktakeScanLine();
                    nl.setBatchId(newBatch.getId());
                    nl.setTaskId(newTask.getId());
                    nl.setSeqNo(++seq);
                    nl.setBoxNo(oldLine.getBoxNo());
                    nl.setLabelNo(oldLine.getLabelNo());
                    nl.setScanPointCode(oldLine.getScanPointCode());
                    nl.setConditionAbnormal(oldLine.getConditionAbnormal());
                    nl.setNote(oldLine.getNote());
                    scanLineMapper.insert(nl);
                }
            }
        }
    }

    private static class StocktakeBatchCarry {
        StocktakeScanBatch batch;
        List<StocktakeScanLine> lines;
    }

    /** 搬运时对同（箱，签）去重，保留最早批次的一条 */
    private List<StocktakeBatchCarry> carryBatches(List<StocktakeScanBatch> batches) {
        Set<String> seen = new HashSet<>();
        List<StocktakeBatchCarry> result = new ArrayList<>();
        for (StocktakeScanBatch batch : batches) {
            List<StocktakeScanLine> lines = scanLineMapper.selectList(
                    new LambdaQueryWrapper<StocktakeScanLine>()
                            .eq(StocktakeScanLine::getBatchId, batch.getId())
                            .orderByAsc(StocktakeScanLine::getSeqNo));
            List<StocktakeScanLine> kept = new ArrayList<>();
            for (StocktakeScanLine line : lines) {
                if (seen.add(line.getBoxNo() + "|" + line.getLabelNo())) {
                    kept.add(line);
                }
            }
            if (!kept.isEmpty()) {
                StocktakeBatchCarry c = new StocktakeBatchCarry();
                c.batch = batch;
                c.lines = kept;
                result.add(c);
            }
        }
        return result;
    }

    private void markSuperseded(StocktakeRound source, StocktakeRound target,
                                String operator, LocalDateTime now) {
        source.setStatus(ROUND_SUPERSEDED);
        roundMapper.updateById(source);
        for (StocktakeTask task : tasksOfRound(source.getId())) {
            if (ST_TASK_REVIEWED.equals(task.getStatus()) || ST_TASK_CLOSED.equals(task.getStatus())) {
                continue;
            }
            task.setStatus(ST_TASK_SUPERSEDED);
            taskMapper.updateById(task);
        }
        ledgerService.append(source.getExhibitionId(), source.getId(), null, null,
                ACTION_ST_SUPERSEDE, source.getRoundNo(),
                "清点版本 " + source.getRoundNo() + " 由新版本 " + target.getRoundNo()
                        + " 重建取代，冻结基准与原结果保留，已接受扫描搬运至新版本", now);
    }

    private StocktakeDiff newDiff(StocktakeRound round, StocktakeTask task, int diffNo,
                                  String type, StocktakeBaselineLine line,
                                  String reasonCode, String reasonDetail) {
        StocktakeDiff diff = new StocktakeDiff();
        diff.setTaskId(task.getId());
        diff.setRoundId(round.getId());
        diff.setDiffNo(diffNo);
        diff.setDiffType(type);
        diff.setReasonCode(reasonCode);
        diff.setReasonDetail(reasonDetail);
        diff.setConfirmStatus(CONFIRM_PENDING);
        diff.setPhotoRequired(ROUND_REPLAY.equals(round.getKind()) ? 0 : 1);
        if (line != null) {
            diff.setRelicId(line.getRelicId());
            diff.setRelicNo(line.getRelicNo());
            diff.setRelicName(line.getRelicName());
            diff.setBaselineLineId(line.getId());
            diff.setExpectedBoxNo(firstCsv(line.getBoxNo()));
            diff.setExpectedLabelNo(line.getDisplayNo());
            diff.setExpectedPointCode(line.getPointCode());
        }
        return diff;
    }

    private StocktakeDiff newLiveDiff(StocktakeRound round, StocktakeTask task, String type,
                                      StocktakeBaselineLine line, String reasonCode,
                                      String reasonDetail) {
        StocktakeDiff diff = newDiff(round, task, 0, type, line, reasonCode, reasonDetail);
        fillLiveDefaults(diff);
        return diff;
    }

    private void fillLiveDefaults(StocktakeDiff diff) {
        diff.setPhotoRequired(1);
        diff.setAutoExplained(0);
    }

    private int countType(List<StocktakeDiff> diffs, String type) {
        return (int) diffs.stream().filter(d -> type.equals(d.getDiffType())).count();
    }

    private List<StocktakeBaselineLine> baselineLines(Long taskId) {
        return baselineMapper.selectList(new LambdaQueryWrapper<StocktakeBaselineLine>()
                .eq(StocktakeBaselineLine::getTaskId, taskId)
                .orderByAsc(StocktakeBaselineLine::getLineNo));
    }

    private List<StocktakeDiff> diffsOfTask(Long taskId) {
        return diffMapper.selectList(new LambdaQueryWrapper<StocktakeDiff>()
                .eq(StocktakeDiff::getTaskId, taskId)
                .orderByAsc(StocktakeDiff::getDiffNo));
    }

    private List<StocktakeTask> tasksOfRound(Long roundId) {
        return taskMapper.selectList(new LambdaQueryWrapper<StocktakeTask>()
                .eq(StocktakeTask::getRoundId, roundId)
                .orderByAsc(StocktakeTask::getHallId));
    }

    private List<Map<String, Object>> supplementsOfRound(Long roundId, Long taskId) {
        List<StocktakeSupplement> supplements = supplementMapper.selectList(
                new LambdaQueryWrapper<StocktakeSupplement>()
                        .eq(StocktakeSupplement::getRoundId, roundId)
                        .eq(taskId != null, StocktakeSupplement::getTaskId, taskId)
                        .orderByAsc(StocktakeSupplement::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StocktakeSupplement s : supplements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("supplement", s);
            item.put("photos", supplementPhotoMapper.selectList(
                    new LambdaQueryWrapper<StocktakeSupplementPhoto>()
                            .eq(StocktakeSupplementPhoto::getSupplementId, s.getId())));
            result.add(item);
        }
        return result;
    }

    // ==================================================================
    // 事务/幂等/校验辅助
    // ==================================================================

    private void insertAttempt(StocktakeRound round, StocktakeTask task, String operation,
                               String requestNo, String operator, Long bizId,
                               LocalDateTime now) {
        StocktakeAttempt attempt = new StocktakeAttempt();
        attempt.setRoundId(round.getId());
        attempt.setTaskId(task == null ? null : task.getId());
        attempt.setOperation(operation);
        attempt.setRequestNo(trimToNull(requestNo));
        attempt.setOperatorName(operator);
        attempt.setDecision(ATTEMPT_EFFECTIVE);
        attempt.setBizId(bizId);
        attempt.setOccurredAt(now);
        attemptMapper.insert(attempt);
    }

    private StocktakeAttempt findAttempt(String requestNo) {
        if (requestNo == null || requestNo.trim().isEmpty()) {
            return null;
        }
        return attemptMapper.selectOne(new LambdaQueryWrapper<StocktakeAttempt>()
                .eq(StocktakeAttempt::getRequestNo, requestNo.trim()));
    }

    private StocktakeRound replayRound(StocktakeAttempt attempt) {
        if (ATTEMPT_REJECTED.equals(attempt.getDecision())) {
            throw new IllegalArgumentException("该请求流水号此前已被拒绝："
                    + attempt.getRejectReason());
        }
        StocktakeRound round = attempt.getBizId() == null ? null
                : roundMapper.selectById(attempt.getBizId());
        if (round == null) {
            throw new IllegalArgumentException("该请求流水号对应的清点版本已不存在，无法重放");
        }
        return round;
    }

    private StocktakeTask replayTaskEntity(StocktakeAttempt attempt, Long taskId) {
        if (ATTEMPT_REJECTED.equals(attempt.getDecision())) {
            throw new IllegalArgumentException("该请求流水号此前已被拒绝："
                    + attempt.getRejectReason());
        }
        StocktakeTask reloaded = attempt.getBizId() == null ? null
                : taskMapper.selectById(attempt.getBizId());
        if (reloaded == null) {
            throw new IllegalArgumentException("该请求流水号对应的清点任务结果已不存在，无法重放");
        }
        return reloaded;
    }

    private Map<String, Object> roundBundle(StocktakeRound round, boolean replayed) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("round", round);
        bundle.put("tasks", tasksOfRound(round.getId()));
        bundle.put("replayed", replayed);
        return bundle;
    }

    private Map<String, Object> scanBundle(StocktakeScanBatch batch, boolean replayed) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("batch", batch);
        bundle.put("lines", scanLineMapper.selectList(new LambdaQueryWrapper<StocktakeScanLine>()
                .eq(StocktakeScanLine::getBatchId, batch.getId())
                .orderByAsc(StocktakeScanLine::getSeqNo)));
        bundle.put("replayed", replayed);
        return bundle;
    }

    private Exhibition lockExhibition(Long exhibitionId) {
        Exhibition exhibition = exhibitionMapper.selectById(exhibitionId);
        if (exhibition == null) {
            throw new IllegalArgumentException("展览不存在: exhibitionId=" + exhibitionId);
        }
        exhibitionMapper.lockRow(exhibitionId);
        return exhibitionMapper.selectById(exhibitionId);
    }

    private void requireOpen(Exhibition exhibition) {
        if (EXH_PLANNED.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 尚在筹备中，请先开放展览后再生成清点版本");
        }
        if (EXH_CLOSED.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 已关闭，不能再生成清点版本");
        }
    }

    private List<Long> exhibitionHallIds(Long exhibitionId) {
        return exhibitionHallMapper.selectList(new LambdaQueryWrapper<ExhibitionHall>()
                        .eq(ExhibitionHall::getExhibitionId, exhibitionId)
                        .orderByAsc(ExhibitionHall::getHallId)).stream()
                .map(ExhibitionHall::getHallId)
                .collect(Collectors.toList());
    }

    private int nextVersionNo(Long exhibitionId) {
        Integer max = roundMapper.selectList(new LambdaQueryWrapper<StocktakeRound>()
                .eq(StocktakeRound::getExhibitionId, exhibitionId)).stream()
                .map(StocktakeRound::getVersionNo)
                .max(Integer::compareTo).orElse(0);
        return max + 1;
    }

    private StocktakeTask requireTask(Long taskId) {
        StocktakeTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("清点任务不存在: taskId=" + taskId);
        }
        return task;
    }

    private StocktakeRound requireRound(Long roundId) {
        StocktakeRound round = roundMapper.selectById(roundId);
        if (round == null) {
            throw new IllegalArgumentException("清点版本不存在: roundId=" + roundId);
        }
        return round;
    }

    private StocktakeDiff requireDiff(Long diffId) {
        StocktakeDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new IllegalArgumentException("清点差异不存在: diffId=" + diffId);
        }
        return diff;
    }

    private BaselineChange change(Long relicId, String relicNo, String type,
                                  String from, String to, Long ignore, String summary) {
        BaselineChange c = new BaselineChange();
        c.relicId = relicId;
        c.relicNo = relicNo;
        c.changeType = type;
        c.fromValue = from;
        c.toValue = to;
        c.summary = summary;
        return c;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String trimToEmpty(String s) {
        return s == null ? null : s.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String firstCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return null;
        }
        int idx = csv.indexOf(',');
        return idx < 0 ? csv.trim() : csv.substring(0, idx).trim();
    }

    private static Set<String> splitCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
