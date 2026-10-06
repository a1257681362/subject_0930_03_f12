package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.Hall;
import com.evops.mapper.HallMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 展览借品清点盘点（museum-12）验收：
 * 展览含多展厅、按展厅冻结基准生成任务；旧台账回放差异自动引用台账且幂等；
 * 正式现场版本扫描箱号/展签比对出缺失/错位/状态异常；
 * 差异强制现场照片+两名不同见证人+解释；借还变更触发版本冲突，重建版本搬运扫描；
 * 全部复核确认前不能关版本/关展览；关闭后仅可追加补充；无数据回放可闭环。
 * 使用独立内存库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-stocktake;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExhibitionStocktakeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HallMapper hallMapper;

    @Test
    void exhibition_stocktake_full_flow_holds() throws Exception {
        // ==================================================================
        // 准备：库区/格位/箱号、文物、展厅、在陈文物、借出、修复中项目
        // ==================================================================
        long areaId = dataOf(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-S\",\"name\":\"清点测试库\",\"tempZone\":\"ORDINARY\","
                + "\"fireRating\":\"F2\",\"loadCapacityKg\":2000}")).get("id").asLong();
        long rackId = dataOf(postJson("/api/storage/racks", "{"
                + "\"rackNo\":\"RK-S\",\"areaId\":" + areaId
                + ",\"equipmentNo\":\"EQ-S\",\"name\":\"清点测试架\"}")).get("id").asLong();

        long r1 = createRelic("ST-001", "清点青铜鼎一");
        long r2 = createRelic("ST-002", "清点青铜鼎二");
        long r3 = createRelic("ST-003", "清点陶碗");
        long r4 = createRelic("ST-004", "清点瓷瓶");
        long r5 = createRelic("ST-005", "清点玉器");
        long r6 = createRelic("ST-006", "清点漆器");

        putBox("SB-1", r1, "SC-1", rackId);
        putBox("SB-2", r2, "SC-2", rackId);
        putBox("SB-3", r3, "SC-3", rackId);
        putBox("SB-4", r4, "SC-4", rackId);
        putBox("SB-5", r5, "SC-5", rackId);

        long h1 = createHall("SH-01", "一号展厅");
        long h2 = createHall("SH-02", "二号展厅");

        display(h1, r1, "P-A", "L-1");
        display(h1, r2, "P-B", "L-2");
        display(h1, r3, "P-C", "L-3");
        display(h2, r4, "P-D", "L-4");
        display(h2, r5, "P-E", "L-5");
        display(h2, r6, "P-F", "L-6");

        // R2 保持借出未还
        long loan2 = dataOf(postJson("/api/loans", "{"
                + "\"loanNo\":\"LO-ST-0001\",\"relicId\":" + r2
                + ",\"borrower\":\"外展借调单位\",\"purpose\":\"外出巡展\"}")).get("id").asLong();

        // R3 推进到修复中（三级文物会签：馆内专家→保管员→外部专家，然后开工）
        long project3 = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-ST-0001\",\"relicId\":" + r3
                + ",\"client\":\"藏品部\",\"plan\":\"清洗加固\"}")).get("id").asLong();
        expectOk(postJson("/api/repair-projects/" + project3 + "/plan-versions", "{"
                + "\"planContent\":\"清洗加固方案\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}"));
        long pv3 = dataOf(getJson("/api/repair-projects/" + project3 + "/plan-versions"))
                .get(0).get("version").get("id").asLong();
        expectOk(signPlan(pv3, "INTERNAL", "馆内专家甲"));
        expectOk(signPlan(pv3, "CUSTODIAN", "保管员丙"));
        expectOk(signPlan(pv3, "EXTERNAL", "外部专家乙"));
        expectOk(postJson("/api/repair-projects/" + project3 + "/start",
                "{\"requestNo\":\"ST-START-1\",\"operatorName\":\"修复师丁\"}"));

        // ==================================================================
        // 1. 建展校验、挂载展厅
        // ==================================================================
        expectFail(postJson("/api/exhibitions",
                "{\"exhibitionNo\":\"EX-ST-1\",\"name\":\"\"}"), "名称不能为空");
        long exhibitionId = dataOf(postJson("/api/exhibitions", "{"
                + "\"exhibitionNo\":\"EX-ST-1\",\"name\":\"清点特展\","
                + "\"venue\":\"主馆一层\",\"curator\":\"策展人戊\"}")).get("id").asLong();
        expectFail(postJson("/api/exhibitions", "{"
                + "\"exhibitionNo\":\"EX-ST-1\",\"name\":\"重号展\"}"), "展览编号已存在");

        // 筹备中不能生成清点版本
        expectFail(postJson("/api/exhibitions/" + exhibitionId + "/rounds/replay",
                "{\"requestNo\":\"RP-EARLY\",\"replayedBy\":\"保管员己\"}"), "筹备中");

        // 挂厅校验：未知厅 / 已关闭厅 / 重复挂载
        expectFail(postJson("/api/exhibitions/" + exhibitionId + "/halls",
                "{\"hallIds\":[999999]}"), "展厅不存在");
        long hallClosed = createHall("SH-X", "已关闭展厅");
        Hall closed = hallMapper.selectById(hallClosed);
        closed.setStatus("CLOSED");
        hallMapper.updateById(closed);
        expectFail(postJson("/api/exhibitions/" + exhibitionId + "/halls",
                "{\"hallIds\":[" + hallClosed + "]}"), "已关闭");
        expectOk(postJson("/api/exhibitions/" + exhibitionId + "/halls",
                "{\"hallIds\":[" + h1 + "," + h2 + "]}"));
        expectFail(postJson("/api/exhibitions/" + exhibitionId + "/halls",
                "{\"hallIds\":[" + h1 + "]}"), "已挂载");
        JsonNode detail = dataOf(getJson("/api/exhibitions/" + exhibitionId));
        assertEquals(2, detail.get("halls").size());

        expectOk(postJson("/api/exhibitions/" + exhibitionId + "/open", "{}"));

        // ==================================================================
        // 2. 旧数据台账回放 v1：MISSING/IN_REPAIR 自动解释，幂等
        // ==================================================================
        JsonNode replay1 = dataOf(postJson(
                "/api/exhibitions/" + exhibitionId + "/rounds/replay", "{"
                        + "\"requestNo\":\"REPLAY-1\",\"replayedBy\":\"档案员庚\"}"));
        long round1Id = replay1.get("round").get("id").asLong();
        assertEquals(1, replay1.get("round").get("versionNo").asInt());
        assertEquals("REPLAY", replay1.get("round").get("kind").asText());
        assertFalse(replay1.get("replayed").asBoolean());
        JsonNode replayTasks = replay1.get("tasks");
        long replayH1Task = taskIdForHall(replayTasks, h1);
        long replayH2Task = taskIdForHall(replayTasks, h2);

        JsonNode replayH1Detail = dataOf(getJson("/api/stocktake-tasks/" + replayH1Task));
        assertEquals("SUBMITTED", replayH1Detail.get("task").get("status").asText());
        JsonNode replayDiffs = replayH1Detail.get("diffs");
        JsonNode missingDiff = findDiff(replayDiffs, "MISSING", "NOT_SCANNED").get("diff");
        assertEquals(1, missingDiff.get("autoExplained").asInt());
        assertEquals(0, missingDiff.get("photoRequired").asInt());
        assertTrue(missingDiff.get("explanation").asText().contains("LO-ST-0001"),
                "回放缺失必须引用借出台账单号");
        assertTrue(missingDiff.get("sourceRef").asText().startsWith("t_relic_ledger#"),
                "回放差异必须引用台账行");
        JsonNode repairDiff = findDiff(replayDiffs, "STATUS_ABNORMAL", "IN_REPAIR").get("diff");
        assertTrue(repairDiff.get("explanation").asText().contains("RP-ST-0001"));
        assertEquals("REPLAY_LEDGER", repairDiff.get("explanationSource").asText());
        // 二号厅无差异
        assertEquals(0, dataOf(getJson("/api/stocktake-tasks/" + replayH2Task))
                .get("diffs").size());

        // 幂等回放：同 requestNo 返回同一版本
        JsonNode replayAgain = dataOf(postJson(
                "/api/exhibitions/" + exhibitionId + "/rounds/replay", "{"
                        + "\"requestNo\":\"REPLAY-1\",\"replayedBy\":\"档案员庚\"}"));
        assertEquals(round1Id, replayAgain.get("round").get("id").asLong());
        assertTrue(replayAgain.get("replayed").asBoolean());

        // 带流水号的非法回放（无厅展览）→ REJECTED 留痕，同号回放原拒绝
        long exhEmpty = dataOf(postJson("/api/exhibitions", "{"
                + "\"exhibitionNo\":\"EX-ST-BAD\",\"name\":\"无厅展\"}")).get("id").asLong();
        expectOk(postJson("/api/exhibitions/" + exhEmpty + "/open", "{}"));
        expectFail(postJson("/api/exhibitions/" + exhEmpty + "/rounds/replay",
                "{\"requestNo\":\"REPLAY-BAD\",\"replayedBy\":\"档案员庚\"}"), "尚未挂载展厅");
        expectFail(postJson("/api/exhibitions/" + exhEmpty + "/rounds/replay",
                "{\"requestNo\":\"REPLAY-BAD\",\"replayedBy\":\"档案员庚\"}"), "此前已被拒绝");
        JsonNode rejectedAttempts = dataOf(getJson(
                "/api/stocktake-attempts?operation=REPLAY"));
        assertTrue(containsDecision(rejectedAttempts, "REJECTED"), "被拒回放必须落尝试审计");

        // 回放版本确认差异 → 复核 → 关闭（回放差异免照片/见证人）
        expectOk(postJson("/api/stocktake-diffs/" + missingDiff.get("id")
                + "/confirm", "{\"confirmedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-diffs/" + repairDiff.get("id")
                + "/confirm", "{\"confirmedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-tasks/" + replayH1Task + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-tasks/" + replayH2Task + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-rounds/" + round1Id + "/close",
                "{\"closedBy\":\"复核组长辛\",\"remark\":\"回放版本关闭\"}"));

        // ==================================================================
        // 3. 正式现场版本 v2：标题固定
        // ==================================================================
        JsonNode round2 = dataOf(postJson("/api/exhibitions/" + exhibitionId + "/rounds",
                "{\"generatedBy\":\"清点员壬\",\"requestNo\":\"LIVE-V2\"}"));
        long round2Id = round2.get("round").get("id").asLong();
        assertEquals(2, round2.get("round").get("versionNo").asInt());
        assertEquals("展览借品清点盘点", round2.get("round").get("title").asText());
        assertEquals("FROZEN", round2.get("round").get("status").asText());
        long task2H1 = taskIdForHall(round2.get("tasks"), h1);
        long task2H2 = taskIdForHall(round2.get("tasks"), h2);
        assertEquals("PENDING", dataOf(getJson("/api/stocktake-tasks/" + task2H1))
                .get("task").get("status").asText());
        assertNotNull(dataOf(getJson("/api/stocktake-tasks/" + task2H1))
                .get("task").get("baselineFingerprint").asText());

        // 4. 扫描：正常、批内重复拒绝
        expectOk(postJson("/api/stocktake-tasks/" + task2H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-1\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\",\"scanPointCode\":\"P-A\"}]}"));
        expectFail(postJson("/api/stocktake-tasks/" + task2H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-DUP\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\"},"
                + "{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\"}]}"), "重复扫描");
        // 状态异常必须填说明
        expectFail(postJson("/api/stocktake-tasks/" + task2H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-ABN\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-9\",\"labelNo\":\"L-9\","
                + "\"conditionAbnormal\":true}]}"), "异常说明");
        // 跨批重复对允许接收（提交时合并计一次）
        expectOk(postJson("/api/stocktake-tasks/" + task2H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-2\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\","
                + "\"scanPointCode\":\"P-A\"}]}"));

        // ==================================================================
        // 5. 盘点中归还 R2 → 版本冲突；预检列出变更；重建 v3 搬运扫描
        // ==================================================================
        expectOk(postJson("/api/loans/" + loan2 + "/return",
                "{\"receiver\":\"库管员子\",\"returnCondition\":\"完好\"}"));
        expectFail(postJson("/api/stocktake-tasks/" + task2H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-CONFLICT\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-2\",\"labelNo\":\"L-2\"}]}"), "版本冲突");
        JsonNode changes = dataOf(getJson(
                "/api/stocktake-tasks/" + task2H1 + "/baseline-changes"));
        assertTrue(changes.get("changed").asBoolean());
        assertTrue(changes.toString().contains("LOAN_CHANGE"));

        // 重建前的额外基准变化：新增同展签文物 L-1（展签复用）、R6 撤展、R5 撤销
        long r7 = createRelic("ST-007", "清点青铜簋");
        display(h1, r7, "P-G", "L-1");
        expectOk(postJson("/api/halls/" + h2 + "/relics/" + r6 + "/withdraw", "{}"));
        expectOk(postJson("/api/relics/" + r5 + "/revoke", "{\"reason\":\"清点测试撤销\"}"));

        JsonNode round3 = dataOf(postJson("/api/exhibitions/" + exhibitionId + "/rounds", "{"
                + "\"generatedBy\":\"清点员壬\",\"requestNo\":\"LIVE-V3\","
                + "\"fromRoundId\":" + round2Id + "}"));
        long round3Id = round3.get("round").get("id").asLong();
        assertEquals(3, round3.get("round").get("versionNo").asInt());
        assertEquals("SUPERSEDED", dataOf(getJson("/api/stocktake-rounds/" + round2Id))
                .get("round").get("status").asText());
        long task3H1 = taskIdForHall(round3.get("tasks"), h1);
        long task3H2 = taskIdForHall(round3.get("tasks"), h2);
        assertEquals("SUPERSEDED", dataOf(getJson("/api/stocktake-tasks/" + task2H1))
                .get("task").get("status").asText());
        // 已接受扫描搬运到新任务，且跨批重复只保留一条
        JsonNode carried = dataOf(getJson("/api/stocktake-tasks/" + task3H1));
        int carriedLines = 0;
        boolean carryMark = false;
        for (JsonNode b : carried.get("batches")) {
            carriedLines += b.get("lines").size();
            if (!b.get("batch").get("carriedFromBatchId").isNull()) {
                carryMark = true;
            }
        }
        assertEquals(1, carriedLines, "跨批重复扫描搬运时合并为一条");
        assertTrue(carryMark, "搬运批次必须记录来源批次");

        // ==================================================================
        // 6. v3 扫描凑齐三类差异
        // ==================================================================
        // 一号厅：R2 点位错位；未知箱签；R4（二号厅文物）跨厅误扫
        // （R1/R7 展签复用由搬运来的 L-1 扫描在提交时判定；R3 漏扫=缺失）
        expectOk(postJson("/api/stocktake-tasks/" + task3H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-V3-H1\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":["
                + "{\"boxNo\":\"SB-2\",\"labelNo\":\"L-2\",\"scanPointCode\":\"P-Z\"},"
                + "{\"boxNo\":\"SB-X\",\"labelNo\":\"L-X\"},"
                + "{\"boxNo\":\"SB-4\",\"labelNo\":\"L-4\",\"scanPointCode\":\"P-A\"}"
                + "]}"));
        // 二号厅：R4 正常；R5 撤销仍在场=状态异常；R6 已撤展仍扫到=状态异常
        expectOk(postJson("/api/stocktake-tasks/" + task3H2 + "/scans", "{"
                + "\"requestNo\":\"SCAN-V3-H2\",\"scannedBy\":\"清点员丑\","
                + "\"scans\":["
                + "{\"boxNo\":\"SB-4\",\"labelNo\":\"L-4\",\"scanPointCode\":\"P-D\"},"
                + "{\"boxNo\":\"SB-5\",\"labelNo\":\"L-5\",\"scanPointCode\":\"P-E\"},"
                + "{\"boxNo\":\"SB-6\",\"labelNo\":\"L-6\",\"scanPointCode\":\"P-F\"}"
                + "]}"));

        JsonNode submitH1 = dataOf(postJson("/api/stocktake-tasks/" + task3H1 + "/submit",
                "{\"requestNo\":\"FINISH-H1\",\"submittedBy\":\"清点员壬\"}"));
        assertEquals("SUBMITTED", submitH1.get("task").get("status").asText());
        assertEquals(1, submitH1.get("task").get("missingCount").asInt());
        assertEquals(4, submitH1.get("task").get("misplacedCount").asInt());
        JsonNode h1Diffs = dataOf(getJson("/api/stocktake-tasks/" + task3H1)).get("diffs");
        JsonNode missing = findDiff(h1Diffs, "MISSING", "NOT_SCANNED");
        assertEquals("ST-003", missing.get("diff").get("relicNo").asText());
        JsonNode wrongPoint = findDiff(h1Diffs, "MISPLACED", "WRONG_POINT");
        assertEquals("ST-002", wrongPoint.get("diff").get("relicNo").asText());
        JsonNode wrongHall = findDiff(h1Diffs, "MISPLACED", "WRONG_HALL");
        assertEquals("ST-004", wrongHall.get("diff").get("relicNo").asText());
        assertEquals(h2, wrongHall.get("diff").get("foundHallId").asLong());
        JsonNode unknown = findDiff(h1Diffs, "MISPLACED", "UNKNOWN_LABEL");
        assertTrue(unknown.get("diff").get("reasonDetail").asText().contains("UNKNOWN_BOX"));
        JsonNode reused = findDiff(h1Diffs, "MISPLACED", "LABEL_REUSED");
        assertTrue(reused.get("diff").get("reasonDetail").asText().contains("ST-001"));
        assertTrue(reused.get("diff").get("reasonDetail").asText().contains("ST-007"));

        JsonNode submitH2 = dataOf(postJson("/api/stocktake-tasks/" + task3H2 + "/submit",
                "{\"requestNo\":\"FINISH-H2\",\"submittedBy\":\"清点员丑\"}"));
        assertEquals(2, submitH2.get("task").get("abnormalCount").asInt());
        JsonNode h2Diffs = dataOf(getJson("/api/stocktake-tasks/" + task3H2)).get("diffs");
        assertEquals("REVOKED", findDiff(h2Diffs, "STATUS_ABNORMAL", "REVOKED")
                .get("diff").get("reasonCode").asText());
        assertEquals("WITHDRAWN_STILL_PRESENT",
                findDiff(h2Diffs, "STATUS_ABNORMAL", "WITHDRAWN_STILL_PRESENT")
                        .get("diff").get("reasonCode").asText());

        // 已提交任务不能重复扫描/提交
        expectFail(postJson("/api/stocktake-tasks/" + task3H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-AFTER\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\"}]}"), "SUBMITTED");
        expectFail(postJson("/api/stocktake-tasks/" + task3H1 + "/submit",
                "{\"requestNo\":\"FINISH-H1-AGAIN\",\"submittedBy\":\"清点员壬\"}"), "不能重复提交");
        // 原 FINISH 流水号回放原结果
        JsonNode finishReplay = dataOf(postJson("/api/stocktake-tasks/" + task3H1 + "/submit",
                "{\"requestNo\":\"FINISH-H1\",\"submittedBy\":\"清点员壬\"}"));
        assertEquals(task3H1, finishReplay.get("task").get("id").asLong());

        // ==================================================================
        // 7. 差异硬规则：无解释/无照片/见证人缺失或相同 → 阻断复核
        // ==================================================================
        expectFail(postJson("/api/stocktake-tasks/" + task3H1 + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"), "尚无解释");

        byte[] photoBytes = pngBytes();
        // 先对 MISSING 差异试解释：无照片被拒
        expectFail(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/explain",
                "{\"explanation\":\"修复后尚未送回展厅，已与修复组核实\","
                        + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\","
                        + "\"explainedBy\":\"保管员寅\"}"), "现场照片");
        // 上传照片
        expectOk(uploadDiffPhoto(missing.get("diff").get("id").asLong(),
                "missing.png", photoBytes, "保管员寅", "现场空位照"));
        // 同内容照片不能重复关联
        expectFail(uploadDiffPhoto(missing.get("diff").get("id").asLong(),
                "missing-copy.png", photoBytes, "保管员寅", "重复"), "内容一致");
        // 见证人相同 / 缺失
        expectFail(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/explain",
                "{\"explanation\":\"修复后尚未送回展厅，已与修复组核实\","
                        + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人甲\","
                        + "\"explainedBy\":\"保管员寅\"}"), "不能为同一人");
        expectFail(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/explain",
                "{\"explanation\":\"修复后尚未送回展厅，已与修复组核实\","
                        + "\"witnessA\":\"见证人甲\",\"explainedBy\":\"保管员寅\"}"), "两名见证人");
        expectOk(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/explain",
                "{\"explanation\":\"修复后尚未送回展厅，已与修复组核实\","
                        + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\","
                        + "\"explainedBy\":\"保管员寅\"}"));
        expectOk(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/confirm",
                "{\"confirmedBy\":\"复核组长辛\"}"));

        // 其余 6 条差异逐条：照片 → 解释（双见证人）→ 确认
        List<JsonNode> remaining = new ArrayList<>();
        for (JsonNode d : h1Diffs) {
            if (d.get("diff").get("id").asLong() != missing.get("diff").get("id").asLong()) {
                remaining.add(d);
            }
        }
        for (JsonNode d : h2Diffs) {
            remaining.add(d);
        }
        assertEquals(6, remaining.size());
        int idx = 0;
        for (JsonNode d : remaining) {
            long diffId = d.get("diff").get("id").asLong();
            expectOk(uploadDiffPhoto(diffId, "d" + (idx++) + ".png",
                    pngBytes(), "保管员寅", "差异现场照"));
            expectOk(postJson("/api/stocktake-diffs/" + diffId + "/explain", "{"
                    + "\"explanation\":\"现场已核对，差异情况属实并已记录处理意见\","
                    + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\","
                    + "\"explainedBy\":\"保管员寅\"}"));
            expectOk(postJson("/api/stocktake-diffs/" + diffId + "/confirm",
                    "{\"confirmedBy\":\"复核组长辛\"}"));
        }

        // ==================================================================
        // 8. 复核 → 关版本 → 关展览的闸门顺序
        // ==================================================================
        // 差异未全确认时关版本（构造已不可能，此处直接验证复核后任务状态）
        expectOk(postJson("/api/stocktake-tasks/" + task3H1 + "/review",
                "{\"reviewedBy\":\"复核组长辛\",\"remark\":\"一号厅差异全部解释确认\"}"));
        // 二号厅未复核 → 版本不能关、展览不能关
        expectFail(postJson("/api/stocktake-rounds/" + round3Id + "/close",
                "{\"closedBy\":\"复核组长辛\"}"), "尚未复核完成");
        expectFail(postJson("/api/exhibitions/" + exhibitionId + "/close",
                "{\"closedBy\":\"馆长卯\"}"), "未复核");
        expectOk(postJson("/api/stocktake-tasks/" + task3H2 + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-rounds/" + round3Id + "/close",
                "{\"closedBy\":\"复核组长辛\"}"));
        // 全部版本关闭/取代后才能关展览
        expectOk(postJson("/api/exhibitions/" + exhibitionId + "/close",
                "{\"closedBy\":\"馆长卯\",\"remark\":\"清点全部闭环\"}"));
        assertEquals("CLOSED", dataOf(getJson("/api/exhibitions/" + exhibitionId))
                .get("exhibition").get("status").asText());

        // ==================================================================
        // 9. 关闭后不可变，只能追加补充（可带照片/见证人）
        // ==================================================================
        expectFail(postJson("/api/stocktake-tasks/" + task3H1 + "/scans", "{"
                + "\"requestNo\":\"SCAN-CLOSED\",\"scannedBy\":\"清点员壬\","
                + "\"scans\":[{\"boxNo\":\"SB-1\",\"labelNo\":\"L-1\"}]}"), "CLOSED");
        expectFail(postJson("/api/stocktake-diffs/" + missing.get("diff").get("id") + "/explain",
                "{\"explanation\":\"关闭后试图改写解释\","
                        + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\","
                        + "\"explainedBy\":\"保管员寅\"}"), "待复核期");
        expectFail(postJson("/api/stocktake-tasks/" + task3H1 + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"), "已随版本关闭");
        // 未关闭版本不能加补充
        long roundLive2Id = round3Id;
        // 补充见证人相同被拒
        expectFail(postJson("/api/stocktake-rounds/" + roundLive2Id + "/supplements", "{"
                + "\"taskId\":" + task3H1 + ",\"content\":\"关闭后复查记录\","
                + "\"submittedBy\":\"保管员寅\","
                + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人甲\"}"), "不能为同一人");
        JsonNode supp = dataOf(postJson("/api/stocktake-rounds/" + roundLive2Id + "/supplements",
                "{"
                        + "\"taskId\":" + task3H1 + ","
                        + "\"diffId\":" + missing.get("diff").get("id") + ","
                        + "\"content\":\"关闭后复查：缺失件已于库房找到，补充说明\","
                        + "\"submittedBy\":\"保管员寅\","
                        + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\"}"));
        long suppId = supp.get("supplement").get("id").asLong();
        expectOk(uploadSupplementPhoto(roundLive2Id, suppId, "supp.png", pngBytes(),
                "保管员寅", "复查照片"));
        // 原差异结果未被改写
        StocktakeDiffHolder holder = new StocktakeDiffHolder();
        holder.diff = dataOf(getJson("/api/stocktake-tasks/" + task3H1));
        JsonNode originalMissing = findDiff(holder.diff.get("diffs"), "MISSING", "NOT_SCANNED");
        assertTrue(originalMissing.get("diff").get("explanation").asText()
                .contains("修复后尚未送回展厅"));
        JsonNode taskDetailAfter = dataOf(getJson("/api/stocktake-tasks/" + task3H1));
        assertEquals(1, taskDetailAfter.get("supplements").size());
        assertEquals(1, taskDetailAfter.get("supplements").get(0).get("photos").size());

        // 台账留痕完整
        JsonNode ledger = dataOf(getJson(
                "/api/stocktake-ledgers?exhibitionId=" + exhibitionId));
        assertTrue(ledger.toString().contains("STOCKTAKE_REPLAY"));
        assertTrue(ledger.toString().contains("STOCKTAKE_BASELINE_CONFLICT")
                || ledger.toString().contains("STOCKTAKE_OP_REJECT"));
        assertTrue(ledger.toString().contains("STOCKTAKE_ROUND_CLOSE"));
        assertTrue(ledger.toString().contains("EXHIBITION_CLOSE"));

        // ==================================================================
        // 10. 空厅展览：回放零差异 + 现场版本零差异，可闭环
        // ==================================================================
        long hallEmpty = createHall("SH-E", "空展厅");
        long exhEmpty2 = dataOf(postJson("/api/exhibitions", "{"
                + "\"exhibitionNo\":\"EX-ST-EMPTY\",\"name\":\"空厅清点展\"}")).get("id").asLong();
        expectOk(postJson("/api/exhibitions/" + exhEmpty2 + "/halls",
                "{\"hallIds\":[" + hallEmpty + "]}"));
        expectOk(postJson("/api/exhibitions/" + exhEmpty2 + "/open", "{}"));
        JsonNode emptyReplay = dataOf(postJson(
                "/api/exhibitions/" + exhEmpty2 + "/rounds/replay", "{"
                        + "\"requestNo\":\"REPLAY-EMPTY\",\"replayedBy\":\"档案员庚\"}"));
        long emptyReplayTask = taskIdForHall(emptyReplay.get("tasks"), hallEmpty);
        assertEquals(0, dataOf(getJson("/api/stocktake-tasks/" + emptyReplayTask))
                .get("task").get("baselineCount").asInt());
        expectOk(postJson("/api/stocktake-tasks/" + emptyReplayTask + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-rounds/"
                + emptyReplay.get("round").get("id") + "/close",
                "{\"closedBy\":\"复核组长辛\"}"));

        JsonNode emptyLive = dataOf(postJson(
                "/api/exhibitions/" + exhEmpty2 + "/rounds",
                "{\"generatedBy\":\"清点员壬\",\"requestNo\":\"LIVE-EMPTY\"}"));
        long emptyLiveTask = taskIdForHall(emptyLive.get("tasks"), hallEmpty);
        assertEquals(0, dataOf(getJson("/api/stocktake-tasks/" + emptyLiveTask))
                .get("task").get("baselineCount").asInt());

        // 11. 并发：同一扫描流水号双线程提交，恰一条生效
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        Runnable scanJob = () -> {
            try {
                start.await();
                MvcResult r = mockMvc.perform(post("/api/stocktake-tasks/" + emptyLiveTask + "/scans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNo\":\"SCAN-CONC\",\"scannedBy\":\"清点员壬\","
                                + "\"scans\":[{\"boxNo\":\"SB-Z\",\"labelNo\":\"L-Z\"}]}"))
                        .andExpect(status().isOk()).andReturn();
                JsonNode resp = objectMapper.readTree(r.getResponse().getContentAsString());
                if (resp.get("success").asBoolean()) {
                    okCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        pool.submit(scanJob);
        pool.submit(scanJob);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(2, okCount.get(), "幂等重放也返回成功，但只落一条批次");
        JsonNode emptyTaskAfter = dataOf(getJson("/api/stocktake-tasks/" + emptyLiveTask));
        int batchCount = emptyTaskAfter.get("batches").size();
        assertEquals(1, batchCount, "同一流水号并发只允许一条扫描批次");
        JsonNode scanAttempts = dataOf(getJson("/api/stocktake-attempts?taskId=" + emptyLiveTask
                + "&operation=SCAN"));
        int effective = 0;
        for (JsonNode a : scanAttempts) {
            if ("EFFECTIVE".equals(a.get("decision").asText())) {
                effective++;
            }
        }
        assertEquals(1, effective, "同流水号并发恰一条 EFFECTIVE 尝试");

        // 并发扫描产生 1 条未知错位差异：照片 → 解释 → 确认后才能复核
        JsonNode emptySubmit = dataOf(postJson("/api/stocktake-tasks/" + emptyLiveTask + "/submit",
                "{\"requestNo\":\"FINISH-EMPTY\",\"submittedBy\":\"清点员壬\"}"));
        assertEquals(1, emptySubmit.get("task").get("misplacedCount").asInt());
        JsonNode emptyDiffs = dataOf(getJson("/api/stocktake-tasks/" + emptyLiveTask))
                .get("diffs");
        for (JsonNode d : emptyDiffs) {
            long diffId = d.get("diff").get("id").asLong();
            expectOk(uploadDiffPhoto(diffId, "empty.png", pngBytes(), "保管员寅", "核查照"));
            expectOk(postJson("/api/stocktake-diffs/" + diffId + "/explain", "{"
                    + "\"explanation\":\"经核查为误入展厅的周转箱，已移走并记录\","
                    + "\"witnessA\":\"见证人甲\",\"witnessB\":\"见证人乙\","
                    + "\"explainedBy\":\"保管员寅\"}"));
            expectOk(postJson("/api/stocktake-diffs/" + diffId + "/confirm",
                    "{\"confirmedBy\":\"复核组长辛\"}"));
        }
        expectOk(postJson("/api/stocktake-tasks/" + emptyLiveTask + "/review",
                "{\"reviewedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/stocktake-rounds/"
                + emptyLive.get("round").get("id") + "/close",
                "{\"closedBy\":\"复核组长辛\"}"));
        expectOk(postJson("/api/exhibitions/" + exhEmpty2 + "/close",
                "{\"closedBy\":\"馆长卯\"}"));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static class StocktakeDiffHolder {
        JsonNode diff;
    }

    private long taskIdForHall(JsonNode tasks, long hallId) {
        for (JsonNode t : tasks) {
            if (t.get("hallId").asLong() == hallId) {
                return t.get("id").asLong();
            }
        }
        throw new AssertionError("未找到展厅任务: hallId=" + hallId);
    }

    private JsonNode findDiff(JsonNode diffBundle, String type, String reasonCode) {
        for (JsonNode d : diffBundle) {
            if (type.equals(d.get("diff").get("diffType").asText())
                    && reasonCode.equals(d.get("diff").get("reasonCode").asText())) {
                return d;
            }
        }
        throw new AssertionError("未找到差异: " + type + "/" + reasonCode
                + "，实际=" + diffBundle.toString());
    }

    private boolean containsDecision(JsonNode attempts, String decision) {
        for (JsonNode a : attempts) {
            if (decision.equals(a.get("decision").asText())) {
                return true;
            }
        }
        return false;
    }

    private long createRelic(String relicNo, String name) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"" + name
                + "\",\"category\":\"测试器\",\"material\":\"测试材质\",\"era\":\"测试年代\","
                + "\"source\":\"测试来源\",\"preservationGrade\":\"THREE\"}")).get("id").asLong();
    }

    private long createHall(String hallNo, String name) throws Exception {
        return dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"" + hallNo + "\",\"name\":\"" + name
                + "\",\"location\":\"测试区\",\"custodian\":\"保管员丙\"}")).get("id").asLong();
    }

    private void display(long hallId, long relicId, String point, String label) throws Exception {
        expectOk(postJson("/api/halls/" + hallId + "/relics", "{"
                + "\"relicId\":" + relicId + ",\"pointCode\":\"" + point
                + "\",\"displayNo\":\"" + label + "\"}"));
    }

    /** 走完整预约流程让一只箱进入当前库存（t_storage_stock） */
    private void putBox(String boxNo, long relicId, String cellNo, long rackId) throws Exception {
        long cellId = dataOf(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"" + cellNo + "\",\"rackId\":" + rackId
                + ",\"shelfNo\":1,\"colNo\":1,\"loadLimitKg\":100}")).get("id").asLong();
        String reqNo = "REQ-" + boxNo;
        // 同一密集架同时段互斥：按箱序号错开预约时间窗
        int day = 2 + Math.abs(boxNo.hashCode() % 20);
        String window = "\"planStartTime\":\"2026-11-" + String.format("%02d", day)
                + " 10:00:00\",\"planEndTime\":\"2026-11-" + String.format("%02d", day)
                + " 11:00:00\"";
        long resId = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"" + reqNo + "\",\"targetCellId\":" + cellId
                + ",\"relicId\":" + relicId + ",\"boxNo\":\"" + boxNo
                + "\",\"weightKg\":10,\"reservedBy\":\"预约员甲\"," + window + "}")).get("id").asLong();
        expectOk(postJson("/api/storage/reservations/" + resId + "/start",
                "{\"operator\":\"搬运工甲\"}"));
        expectOk(postJson("/api/storage/reservations/" + resId + "/complete", "{}"));
    }

    private MvcResult signPlan(long versionId, String role, String name) throws Exception {
        return postJson("/api/plan-versions/" + versionId + "/sign", "{"
                + "\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                + "\",\"decision\":\"APPROVE\",\"opinion\":\"同意\"}");
    }

    private MvcResult uploadDiffPhoto(long diffId, String fileName, byte[] bytes,
                                      String uploadedBy, String remark) throws Exception {
        return mockMvc.perform(multipart("/api/stocktake-diffs/" + diffId + "/photos")
                .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_PNG_VALUE, bytes))
                .param("uploadedBy", uploadedBy)
                .param("remark", remark))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult uploadSupplementPhoto(long roundId, long suppId, String fileName,
                                            byte[] bytes, String uploadedBy,
                                            String remark) throws Exception {
        return mockMvc.perform(multipart(
                "/api/stocktake-rounds/" + roundId + "/supplements/" + suppId + "/photos")
                .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_PNG_VALUE, bytes))
                .param("uploadedBy", uploadedBy)
                .param("remark", remark))
                .andExpect(status().isOk()).andReturn();
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(node.get("success").asBoolean(),
                "期望成功但失败: " + result.getResponse().getContentAsString());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(node.get("success").asBoolean(),
                "期望失败但成功: " + result.getResponse().getContentAsString());
        assertNotNull(node.get("message"));
        assertTrue(node.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际：" + node.get("message").asText());
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(node.get("success").asBoolean(),
                "期望成功但失败: " + result.getResponse().getContentAsString());
        return node.get("data");
    }
}
