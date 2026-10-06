package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.StocktakeAttempt;
import com.evops.mapper.StocktakeAttemptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 清点被拒尝试的独立审计：强制 REQUIRES_NEW。
 * 业务事务因版本冲突/状态不允许回滚（扫描行等业务写入全部撤销）后，
 * "谁的清点操作被拒绝、为什么"这条审计仍独立保留，并同时落清点台账。
 */
@Service
public class StocktakeAttemptAuditService {

    private static final Logger log = LoggerFactory.getLogger(StocktakeAttemptAuditService.class);

    private final StocktakeAttemptMapper attemptMapper;
    private final StocktakeLedgerService ledgerService;

    public StocktakeAttemptAuditService(StocktakeAttemptMapper attemptMapper,
                                        StocktakeLedgerService ledgerService) {
        this.attemptMapper = attemptMapper;
        this.ledgerService = ledgerService;
    }

    /**
     * 记录一次被拒绝的清点操作尝试。独立事务提交，不受调用方回滚影响。
     * 已存在同 request_no 的尝试（含 EFFECTIVE）时不再插入。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long roundId, Long taskId, String operation, String requestNo,
                               String operatorName, String reason, Long exhibitionId, Long hallId) {
        if (requestNo != null && !requestNo.trim().isEmpty()) {
            StocktakeAttempt exists = attemptMapper.selectOne(
                    new LambdaQueryWrapper<StocktakeAttempt>()
                            .eq(StocktakeAttempt::getRequestNo, requestNo));
            if (exists != null) {
                return;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        StocktakeAttempt attempt = new StocktakeAttempt();
        attempt.setRoundId(roundId);
        attempt.setTaskId(taskId);
        attempt.setOperation(operation);
        attempt.setRequestNo(requestNo);
        attempt.setOperatorName(operatorName);
        attempt.setDecision("REJECTED");
        attempt.setRejectReason(reason);
        attempt.setOccurredAt(now);
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            log.warn("清点被拒尝试遇到重复流水号，跳过: operation={}, requestNo={}",
                    operation, requestNo);
            return;
        }
        ledgerService.append(exhibitionId, roundId, taskId, hallId,
                "STOCKTAKE_OP_REJECT", requestNo,
                "清点操作「" + operationName(operation) + "」被拒绝"
                        + (operatorName == null ? "" : "，操作人：" + operatorName)
                        + "，拒绝原因：" + reason, now);
    }

    private String operationName(String operation) {
        switch (operation) {
            case "REPLAY": return "旧数据回放";
            case "ROUND_CREATE": return "生成清点版本";
            case "SCAN": return "扫描提交";
            case "FINISH": return "清点提交";
            case "REVIEW": return "任务复核";
            case "EXPLAIN": return "差异解释";
            case "CONFIRM": return "差异确认";
            case "CLOSE_ROUND": return "关闭清点版本";
            case "SUPPLEMENT": return "追加补充记录";
            default: return operation;
        }
    }
}
