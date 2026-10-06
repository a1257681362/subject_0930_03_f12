package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.ExhibitionCloseRequest;
import com.evops.dto.ExhibitionCreateRequest;
import com.evops.dto.ExhibitionHallAttachRequest;
import com.evops.entity.Exhibition;
import com.evops.entity.ExhibitionHall;
import com.evops.entity.Hall;
import com.evops.entity.StocktakeRound;
import com.evops.entity.StocktakeTask;
import com.evops.mapper.ExhibitionHallMapper;
import com.evops.mapper.ExhibitionMapper;
import com.evops.mapper.HallMapper;
import com.evops.mapper.StocktakeRoundMapper;
import com.evops.mapper.StocktakeTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 展览服务：建展、挂载展厅、开放/关闭。
 * 关闭硬闸门：存在清点版本且全部版本 CLOSED/SUPERSEDED（即非取代版本的全部
 * 展厅任务均已复核），否则拒绝关闭——清点复核未完成不能关闭展览。
 */
@Service
public class ExhibitionService {

    private final ExhibitionMapper exhibitionMapper;
    private final ExhibitionHallMapper exhibitionHallMapper;
    private final HallMapper hallMapper;
    private final StocktakeRoundMapper roundMapper;
    private final StocktakeTaskMapper taskMapper;
    private final StocktakeLedgerService ledgerService;

    public ExhibitionService(ExhibitionMapper exhibitionMapper,
                             ExhibitionHallMapper exhibitionHallMapper,
                             HallMapper hallMapper,
                             StocktakeRoundMapper roundMapper,
                             StocktakeTaskMapper taskMapper,
                             StocktakeLedgerService ledgerService) {
        this.exhibitionMapper = exhibitionMapper;
        this.exhibitionHallMapper = exhibitionHallMapper;
        this.hallMapper = hallMapper;
        this.roundMapper = roundMapper;
        this.taskMapper = taskMapper;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Exhibition create(ExhibitionCreateRequest req) {
        Exhibition exists = exhibitionMapper.selectOne(new LambdaQueryWrapper<Exhibition>()
                .eq(Exhibition::getExhibitionNo, req.getExhibitionNo().trim()));
        if (exists != null) {
            throw new IllegalArgumentException("展览编号已存在: " + req.getExhibitionNo());
        }
        Exhibition exhibition = new Exhibition();
        exhibition.setExhibitionNo(req.getExhibitionNo().trim());
        exhibition.setName(req.getName().trim());
        exhibition.setTheme(req.getTheme());
        exhibition.setVenue(req.getVenue());
        exhibition.setStartDate(req.getStartDate());
        exhibition.setEndDate(req.getEndDate());
        exhibition.setCurator(req.getCurator());
        exhibition.setStatus(EXH_PLANNED);
        exhibitionMapper.insert(exhibition);
        ledgerService.append(exhibition.getId(), null, null, null,
                ACTION_EXH_CREATE, exhibition.getExhibitionNo(),
                "创建展览 " + exhibition.getExhibitionNo() + "（" + exhibition.getName()
                        + "），状态筹备中", LocalDateTime.now());
        return exhibition;
    }

    public List<Exhibition> list(String status) {
        return exhibitionMapper.selectList(new LambdaQueryWrapper<Exhibition>()
                .eq(status != null && !status.isEmpty(), Exhibition::getStatus, status)
                .orderByAsc(Exhibition::getId));
    }

    public Exhibition requireExhibition(Long id) {
        Exhibition exhibition = exhibitionMapper.selectById(id);
        if (exhibition == null) {
            throw new IllegalArgumentException("展览不存在: exhibitionId=" + id);
        }
        return exhibition;
    }

    /** 展览详情：展览 + 展厅列表 */
    public Map<String, Object> detail(Long id) {
        Exhibition exhibition = requireExhibition(id);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exhibition", exhibition);
        bundle.put("halls", hallsOfExhibition(id));
        return bundle;
    }

    public List<Hall> hallsOfExhibition(Long exhibitionId) {
        List<Long> hallIds = exhibitionHallMapper.selectList(
                        new LambdaQueryWrapper<ExhibitionHall>()
                                .eq(ExhibitionHall::getExhibitionId, exhibitionId)
                                .orderByAsc(ExhibitionHall::getHallId)).stream()
                .map(ExhibitionHall::getHallId).collect(java.util.stream.Collectors.toList());
        List<Hall> halls = new ArrayList<>();
        for (Long hallId : hallIds) {
            Hall hall = hallMapper.selectById(hallId);
            if (hall != null) {
                halls.add(hall);
            }
        }
        return halls;
    }

    /** 挂载展厅：厅须存在且 ACTIVE，重复挂载拒绝；展览关闭后不可挂 */
    @Transactional
    public List<Hall> attachHalls(Long exhibitionId, ExhibitionHallAttachRequest req) {
        exhibitionMapper.lockRow(exhibitionId);
        Exhibition exhibition = requireExhibition(exhibitionId);
        if (EXH_CLOSED.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 已关闭，不能再挂载展厅");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long hallId : req.getHallIds()) {
            Hall hall = hallMapper.selectById(hallId);
            if (hall == null) {
                throw new IllegalArgumentException("展厅不存在: hallId=" + hallId);
            }
            if (!HALL_ACTIVE.equals(hall.getStatus())) {
                throw new IllegalArgumentException("展厅 " + hall.getHallNo()
                        + " 已关闭，不能挂载到展览");
            }
            ExhibitionHall existing = exhibitionHallMapper.selectOne(
                    new LambdaQueryWrapper<ExhibitionHall>()
                            .eq(ExhibitionHall::getExhibitionId, exhibitionId)
                            .eq(ExhibitionHall::getHallId, hallId));
            if (existing != null) {
                throw new IllegalArgumentException("展厅 " + hall.getHallNo()
                        + " 已挂载到展览 " + exhibition.getExhibitionNo());
            }
            ExhibitionHall link = new ExhibitionHall();
            link.setExhibitionId(exhibitionId);
            link.setHallId(hallId);
            link.setHallNo(hall.getHallNo());
            exhibitionHallMapper.insert(link);
            ledgerService.append(exhibitionId, null, null, hallId,
                    ACTION_EXH_HALL_ATTACH, hall.getHallNo(),
                    "展览 " + exhibition.getExhibitionNo() + " 挂载展厅 " + hall.getHallNo()
                            + "（" + hall.getName() + "）", now);
        }
        return hallsOfExhibition(exhibitionId);
    }

    @Transactional
    public Exhibition open(Long id) {
        exhibitionMapper.lockRow(id);
        Exhibition exhibition = requireExhibition(id);
        if (EXH_OPEN.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo() + " 已开放");
        }
        if (EXH_CLOSED.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 已关闭，不能重新开放");
        }
        exhibition.setStatus(EXH_OPEN);
        exhibitionMapper.updateById(exhibition);
        ledgerService.append(id, null, null, null, ACTION_EXH_OPEN,
                exhibition.getExhibitionNo(), "展览 " + exhibition.getExhibitionNo() + " 开放",
                LocalDateTime.now());
        return exhibition;
    }

    /**
     * 关闭展览硬闸门：
     * 至少有一个清点版本，且所有版本均 CLOSED 或 SUPERSEDED；
     * 非取代版本必须全部关闭（其全部展厅任务已复核、差异已确认）。
     */
    @Transactional
    public Exhibition close(Long id, ExhibitionCloseRequest req) {
        exhibitionMapper.lockRow(id);
        Exhibition exhibition = requireExhibition(id);
        if (EXH_CLOSED.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo() + " 已关闭");
        }
        if (!EXH_OPEN.equals(exhibition.getStatus())) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 尚未开放，不能关闭");
        }
        List<StocktakeRound> rounds = roundMapper.selectList(new LambdaQueryWrapper<StocktakeRound>()
                .eq(StocktakeRound::getExhibitionId, id)
                .orderByAsc(StocktakeRound::getVersionNo));
        if (rounds.isEmpty()) {
            throw new IllegalArgumentException("展览 " + exhibition.getExhibitionNo()
                    + " 尚未进行过借品清点，不能关闭");
        }
        for (StocktakeRound round : rounds) {
            if (ROUND_CLOSED.equals(round.getStatus()) || ROUND_SUPERSEDED.equals(round.getStatus())) {
                continue;
            }
            List<StocktakeTask> tasks = taskMapper.selectList(
                    new LambdaQueryWrapper<StocktakeTask>()
                            .eq(StocktakeTask::getRoundId, round.getId()));
            long unreviewed = tasks.stream()
                    .filter(t -> !ST_TASK_REVIEWED.equals(t.getStatus())
                            && !ST_TASK_SUPERSEDED.equals(t.getStatus()))
                    .count();
            throw new IllegalArgumentException("清点版本 " + round.getRoundNo()
                    + "（v" + round.getVersionNo() + "）尚未关闭，仍有 "
                    + (unreviewed > 0 ? unreviewed + " 个展厅任务未复核；" : "")
                    + "全部清点复核完成并确认差异前不能关闭展览");
        }
        LocalDateTime now = LocalDateTime.now();
        exhibition.setStatus(EXH_CLOSED);
        exhibition.setClosedBy(req.getClosedBy().trim());
        exhibition.setClosedAt(now);
        exhibition.setCloseRemark(req.getRemark());
        exhibitionMapper.updateById(exhibition);
        ledgerService.append(id, null, null, null, ACTION_EXH_CLOSE,
                exhibition.getExhibitionNo(), "展览 " + exhibition.getExhibitionNo()
                        + " 关闭：全部清点版本已关闭或被取代，关闭人 " + req.getClosedBy(), now);
        return exhibition;
    }
}
