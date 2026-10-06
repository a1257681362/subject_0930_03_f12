package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.StocktakeLedger;
import com.evops.mapper.StocktakeLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清点台账：只追加、不修改、不删除。
 * 建展/挂厅/开关幕/版本生成/回放/扫描/版本冲突/提交/复核/解释/确认/关版本/补充
 * 等全部动作留痕，关联展览/版本/任务/展厅，清点全过程可追溯。
 */
@Service
public class StocktakeLedgerService {

    private final StocktakeLedgerMapper ledgerMapper;

    public StocktakeLedgerService(StocktakeLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long exhibitionId, Long roundId, Long taskId, Long hallId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        StocktakeLedger entry = new StocktakeLedger();
        entry.setExhibitionId(exhibitionId);
        entry.setRoundId(roundId);
        entry.setTaskId(taskId);
        entry.setHallId(hallId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    public List<StocktakeLedger> list(Long exhibitionId, Long roundId, Long hallId) {
        LambdaQueryWrapper<StocktakeLedger> wrapper = new LambdaQueryWrapper<StocktakeLedger>()
                .orderByAsc(StocktakeLedger::getOccurredAt)
                .orderByAsc(StocktakeLedger::getId);
        if (exhibitionId != null) {
            wrapper.eq(StocktakeLedger::getExhibitionId, exhibitionId);
        }
        if (roundId != null) {
            wrapper.eq(StocktakeLedger::getRoundId, roundId);
        }
        if (hallId != null) {
            wrapper.eq(StocktakeLedger::getHallId, hallId);
        }
        return ledgerMapper.selectList(wrapper);
    }
}
