package com.blankgrid.aione.service;

import com.blankgrid.aione.entity.Activity;
import com.blankgrid.aione.entity.Deliverable;
import com.blankgrid.aione.entity.Employee;
import com.blankgrid.aione.entity.Goal;
import com.blankgrid.aione.entity.Workstream;
import com.blankgrid.aione.repo.ActivityRepository;
import com.blankgrid.aione.repo.DeliverableRepository;
import com.blankgrid.aione.repo.EmployeeRepository;
import com.blankgrid.aione.repo.GoalRepository;
import com.blankgrid.aione.repo.WorkstreamRepository;
import com.blankgrid.aione.ws.EventPusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上报(ESCALATION)的收敛:一件事只有一条,问题没了那条自己消失。
 *
 * <p>原来的实现是「追加 + 手动已阅」,于是攒出 252 条全 OPEN 的上报 ——
 * 其中 92 条是同一句「大模型账户余额不足」,54 条是同一个目标反复问
 * 「需要你先确认 N 件事」。而这个数字是界面上唯一的「有多少事等你拍板」,
 * 一旦灌满,真正要决策的那一条就再也浮不上来了。
 *
 * <p>两条规则:
 * <ul>
 *   <li><b>一个主体一条。</b>同一份交付物第二次受阻,不新增,只更新原因、次数与时间。
 *       老板要看的是「哪几件事卡着」,不是「它试了几次」。</li>
 *   <li><b>问题消失即关闭。</b>交付物通过了、工作流跑起来了、目标被暂停了,
 *       对应的上报自动置 {@link Activity#RESOLVED}。人没管但它不再是问题 ——
 *       这和「已阅」是两回事,混成一个值就再也答不上「我忽略了多少」。</li>
 * </ul>
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    /** 服务商侧不可用是全局的,不挂在任何目标上 */
    public static final String LLM = "sys:llm";

    private static final Pattern QUOTED = Pattern.compile("「(.{2,120}?)」");

    private final ActivityRepository repo;
    private final DeliverableRepository delRepo;
    private final WorkstreamRepository wsRepo;
    private final GoalRepository goalRepo;
    private final EmployeeRepository empRepo;
    private final EventPusher pusher;

    public EscalationService(ActivityRepository repo, DeliverableRepository delRepo,
                             WorkstreamRepository wsRepo, GoalRepository goalRepo,
                             EmployeeRepository empRepo, EventPusher pusher) {
        this.repo = repo;
        this.delRepo = delRepo;
        this.wsRepo = wsRepo;
        this.goalRepo = goalRepo;
        this.empRepo = empRepo;
        this.pusher = pusher;
    }

    // ==================== 主体键 ====================

    public static String ofDeliverable(Long id) { return "d:" + id; }
    public static String ofDeliverableFacts(Long id) { return "d:" + id + ":facts"; }
    public static String ofWorkstream(Long id) { return "w:" + id; }
    public static String ofGoal(Long id, String aspect) { return "g:" + id + ":" + aspect; }
    public static String ofEmployee(Long id) { return "emp:" + id; }

    /**
     * 「请创始人拍板」这类问题。按目标收敛,不按措辞 ——
     * CEO 每轮都会换一种说法把同一件事再问一遍,措辞去重拦不住。
     */
    public static String ofAsk(Long goalId) { return goalId == null ? "ask:global" : "g:" + goalId + ":ask"; }

    // ==================== 写 ====================

    /**
     * 上报一件事。同一主体已有 OPEN 的,只更新,不再新增。
     *
     * @return 真正落库的那条(新建或更新后的)
     */
    @Transactional
    public Activity raise(String key, Long goalId, String content) {
        if (key == null || key.isBlank()) {
            // 没有主体的上报(例如 CEO 泛泛地上报)各自独立,不参与收敛
            Activity a = new Activity(Activity.ESCALATION, content, null);
            a.setGoalId(goalId);
            return repo.save(a);
        }
        List<Activity> same = repo.findByDedupeKeyAndStatusOrderByIdDesc(key, Activity.OPEN);
        if (same.isEmpty()) {
            Activity a = new Activity(Activity.ESCALATION, content, null);
            a.setGoalId(goalId);
            a.setDedupeKey(key);
            a.setOccurrences(1);
            a.setLastSeenAt(Instant.now());
            return repo.save(a);
        }
        Activity keep = same.get(0);
        // 老数据合并时可能出现同主体多条 OPEN:留最新那条,其余标成已收敛
        for (int i = 1; i < same.size(); i++) {
            Activity dup = same.get(i);
            dup.setStatus(Activity.RESOLVED);
            dup.setContent(clip(dup.getContent() + "(已合并到 #" + keep.getId() + ")", 8000));
            repo.save(dup);
        }
        keep.setContent(content);
        keep.setOccurrences(keep.getOccurrences() + 1);
        keep.setLastSeenAt(Instant.now());
        if (keep.getGoalId() == null && goalId != null) keep.setGoalId(goalId);
        return repo.save(keep);
    }

    /** 主体好了:把它挂着的 OPEN 上报全部关掉。返回关了几条。 */
    @Transactional
    public int resolve(String key) {
        if (key == null || key.isBlank()) return 0;
        List<Activity> open = repo.findByDedupeKeyAndStatusOrderByIdDesc(key, Activity.OPEN);
        for (Activity a : open) {
            a.setStatus(Activity.RESOLVED);
            a.setLastSeenAt(Instant.now());
            repo.save(a);
        }
        if (!open.isEmpty()) {
            log.info("上报已自动关闭:{} ({} 条)", key, open.size());
            pushCount();
        }
        return open.size();
    }

    // ==================== 对账 ====================

    /**
     * 扫一遍所有 OPEN 上报,凡主体已经不在问题状态的,一律关掉。
     *
     * <p>不能只靠在各个「变好」的入口逐个调 resolve —— 状态会从很多地方变
     * (评审通过、人工重试、追加轮次、砍掉、暂停、恢复),漏一个就是永久挂着的
     * 假待办。这里是兜底,每次巡视跑一遍。
     */
    @Transactional
    public int sweep() {
        int closed = 0;
        for (Activity a : repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)) {
            String key = a.getDedupeKey();
            if (key == null || key.isBlank()) continue;      // 还没补键的历史数据,留给 backfill
            if (!gone(key)) continue;
            a.setStatus(Activity.RESOLVED);
            a.setLastSeenAt(Instant.now());
            repo.save(a);
            closed++;
        }
        if (closed > 0) {
            log.info("对账关闭 {} 条已经不再成立的上报", closed);
            pushCount();
        }
        return closed;
    }

    /** 这个主体现在还是不是个问题 */
    boolean gone(String key) {
        if (key.startsWith("d:")) return deliverableGone(key);
        if (key.startsWith("w:")) return workstreamGone(key);
        if (key.startsWith("g:")) return goalGone(key);
        if (key.startsWith("emp:")) return employeeGone(key);
        return false;   // sys:llm 与 CEO 自主上报:只有外部确认或创始人已阅能关
    }

    private boolean deliverableGone(String key) {
        String[] parts = key.split(":");
        Long id = parseId(parts, 1);
        if (id == null) return false;
        Deliverable d = delRepo.findById(id).orElse(null);
        if (d == null) return true;
        if (Deliverable.DONE.equals(d.getStatus()) || Deliverable.CANCELLED.equals(d.getStatus())) return true;
        boolean facts = parts.length > 2 && "facts".equals(parts[2]);
        if (facts) return !Deliverable.WAITING.equals(d.getStatus());
        // 受阻上报:只要它已经重新跑起来(或重新排队),「卡住」这件事就不成立了
        return !Deliverable.BLOCKED.equals(d.getStatus());
    }

    private boolean workstreamGone(String key) {
        Long id = parseId(key.split(":"), 1);
        if (id == null) return false;
        Workstream ws = wsRepo.findById(id).orElse(null);
        return ws == null || !Workstream.BLOCKED.equals(ws.getStatus());
    }

    private boolean goalGone(String key) {
        String[] parts = key.split(":");
        Long id = parseId(parts, 1);
        if (id == null) return false;
        Goal g = goalRepo.findById(id).orElse(null);
        if (g == null) return true;
        String s = g.getStatus();
        if (Goal.DONE.equals(s) || Goal.FAILED.equals(s)) return true;
        String aspect = parts.length > 2 ? parts[2] : "";
        return switch (aspect) {
            // 均分不够:目标不再是 REVIEW 就说明这个判断已经过期
            case "score" -> !Goal.REVIEW.equals(s);
            // 预算用尽:只有重新跑起来(或已完成)才算好了 —— 暂停正是它造成的状态
            case "budget" -> Goal.ACTIVE.equals(s);
            // 缺料:目标停了就不急,补上料也不再是问题
            case "facts" -> Goal.PAUSED.equals(s) || g.getNeedsInput() == null || g.getNeedsInput().isBlank();
            default -> Goal.PAUSED.equals(s);
        };
    }

    private boolean employeeGone(String key) {
        Long id = parseId(key.split(":"), 1);
        if (id == null) return false;
        Employee e = empRepo.findById(id).orElse(null);
        return e == null || !Employee.STATUS_DISABLED.equals(e.getStatus());
    }

    private Long parseId(String[] parts, int idx) {
        if (parts.length <= idx) return null;
        try {
            return Long.parseLong(parts[idx]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 历史数据 ====================

    /**
     * 给没有主体键的历史上报补键,并把重复的合并掉。
     *
     * <p>只能靠正文里那句标题去反查 —— 老数据没存 id。反查不到的就按正文指纹分组,
     * 至少让完全相同的两条变成一条。跑多少次都安全:只处理没有键的。
     *
     * @return 补上的条数
     */
    @Transactional
    public int backfill() {
        int fixed = 0;
        for (Activity a : repo.findByTypeAndDedupeKeyIsNullOrderByIdAsc(Activity.ESCALATION)) {
            String key = inferKey(a);
            a.setDedupeKey(key);
            a.setOccurrences(Math.max(1, a.getOccurrences()));
            if (a.getLastSeenAt() == null) a.setLastSeenAt(a.getCreatedAt());
            repo.save(a);
            fixed++;
            // 同一主体的多条老记录:只留最新的 OPEN,其余标已合并
            if (Activity.OPEN.equals(a.getStatus())) {
                List<Activity> open = repo.findByDedupeKeyAndStatusOrderByIdDesc(key, Activity.OPEN);
                for (int i = 1; i < open.size(); i++) {
                    Activity dup = open.get(i);
                    dup.setStatus(Activity.RESOLVED);
                    repo.save(dup);
                    a.setOccurrences(a.getOccurrences() + 1);
                    repo.save(a);
                }
            }
        }
        if (fixed > 0) log.info("历史上报补主体键:{} 条", fixed);
        // 上一版的归并规则给过一批 old:/ceo: 键,主体认得不够准,重算一遍
        int rekeyed = rekeyOld();
        if (rekeyed > 0) log.info("历史上报重算主体键:{} 条", rekeyed);
        collapse();
        return fixed;
    }

    /**
     * 同一主体还有多条 OPEN 的,合成一条。
     *
     * <p>补键之后才会出现的局面:40 条措辞不同的「请拍板」现在有了同一个键,
     * 得把它们并成一条,并记下「总共被问了多少次」。
     */
    private void collapse() {
        Map<String, List<Activity>> byKey = new LinkedHashMap<>();
        for (Activity a : repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)) {
            String k = a.getDedupeKey();
            if (k == null || k.isBlank() || k.startsWith("old:")) continue;
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(a);
        }
        int merged = 0;
        for (Map.Entry<String, List<Activity>> e : byKey.entrySet()) {
            List<Activity> list = e.getValue();
            if (list.size() < 2) continue;
            list.sort((x, y) -> Long.compare(y.getId(), x.getId()));
            Activity keep = list.get(0);
            int total = 0;
            for (Activity a : list) total += a.getOccurrences();
            keep.setOccurrences(total);
            keep.setLastSeenAt(Instant.now());
            repo.save(keep);
            for (int i = 1; i < list.size(); i++) {
                Activity dup = list.get(i);
                dup.setStatus(Activity.RESOLVED);
                dup.setContent(clip(dup.getContent() + "(已合并到 #" + keep.getId() + ")", 8000));
                repo.save(dup);
                merged++;
            }
            log.info("上报合并:{} 条并为 #{} (共发生 {} 次)", list.size(), keep.getId(), total);
        }
        if (merged > 0) log.info("历史上报去重:合并掉 {} 条", merged);
    }

    /** 规则改过之后,给此前认错主体的旧键重算一遍 */
    private int rekeyOld() {
        int n = 0;
        for (Activity a : repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN)) {
            String k = a.getDedupeKey();
            if (k == null) continue;
            if (!k.startsWith("old:") && !k.startsWith("ceo:")) continue;
            a.setDedupeKey(inferKey(a));
            repo.save(a);
            n++;
        }
        return n;
    }

    /** 从正文里猜主体。 */
    String inferKey(Activity a) {
        String c = a.getContent() == null ? "" : a.getContent();
        Long goalId = a.getGoalId();

        if (c.contains("大模型账户")) return LLM;

        if (c.contains("需要你先确认")) {
            return goalId == null ? ofAsk(null) : ofGoal(goalId, "facts");
        }
        if (c.contains("低于质量线")) {
            return goalId == null ? ofAsk(null) : ofGoal(goalId, "score");
        }
        if (c.contains("token 预算用尽")) {
            return goalId == null ? ofAsk(null) : ofGoal(goalId, "budget");
        }

        if (c.startsWith("工作流「")) {
            Workstream ws = workstreamByTitle(quoted(c));
            return ws == null ? ofAsk(goalId) : ofWorkstream(ws.getId());
        }

        if (c.startsWith("岗位「")) {
            Employee e = employeeByName(quoted(c));
            return e == null ? ofAsk(goalId) : ofEmployee(e.getId());
        }

        // 交付物有两种句式:「交付物「x」受阻:」 和 「「x」(72 分)缺事实」
        Deliverable d = deliverableByTitle(quoted(c));
        if (d != null) {
            boolean facts = c.contains("缺事实") || c.contains("补料");
            return facts ? ofDeliverableFacts(d.getId()) : ofDeliverable(d.getId());
        }

        // 剩下的全是 CEO 自己组织语言写的上报 —— 它每轮都会换一种说法重问同一件事。
        // 关键词匹配拦不住(实测同一件「域名与备案号」有四十多种措辞),所以按目标归并:
        // 同一目标下的「请拍板」就是一件事,换句话说不该变成第二条待办。
        // 新数据不会走到这里:上面几类都有确切的主体键。
        return ofAsk(goalId);
    }

    /** 正文里第一个「」里的标题。老上报只留了这句标题,没留 id */
    static String quoted(String content) {
        Matcher m = QUOTED.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private Deliverable deliverableByTitle(String title) {
        if (title.isBlank()) return null;
        List<Deliverable> list = delRepo.findByTitleOrderByIdDesc(title);
        return list.isEmpty() ? null : list.get(0);
    }

    private Workstream workstreamByTitle(String title) {
        if (title.isBlank()) return null;
        List<Workstream> list = wsRepo.findByTitleOrderByIdDesc(title);
        return list.isEmpty() ? null : list.get(0);
    }

    private Employee employeeByName(String name) {
        if (name.isBlank()) return null;
        List<Employee> list = empRepo.findByNameOrderByIdDesc(name);
        return list.isEmpty() ? null : list.get(0);
    }

    private void pushCount() {
        try {
            pusher.push("escalation", java.util.Map.of("count",
                    repo.countByTypeAndStatus(Activity.ESCALATION, Activity.OPEN)));
        } catch (Exception e) {
            log.debug("推送上报计数失败(不影响业务):{}", e.getMessage());
        }
    }

    // ==================== 读 ====================

    /** 现在还成立的待办:按最后一次发生时间倒序,最近又冒头的排前面 */
    public List<Activity> open() {
        Set<Activity> set = new LinkedHashSet<>(
                repo.findByTypeAndStatusOrderByIdAsc(Activity.ESCALATION, Activity.OPEN));
        return set.stream()
                .sorted((x, y) -> y.getLastSeenAt().compareTo(x.getLastSeenAt()))
                .toList();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
