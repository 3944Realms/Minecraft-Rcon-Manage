package cc.endmc.quartz.task;

import cc.endmc.server.common.constant.BotApi;
import cc.endmc.server.domain.bot.QqBotConfig;
import cc.endmc.server.domain.permission.WhitelistInfo;
import cc.endmc.server.service.permission.IWhitelistInfoService;
import cc.endmc.server.ws.BotClient;
import cc.endmc.server.ws.BotManager;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 机器人定时任务
 * 主要用于监控白名单用户是否退群
 */
@Slf4j
@Component("botTask")
public class BotTask {
    @Autowired
    private IWhitelistInfoService whitelistInfoService;

    @Autowired
    private BotManager botManager;

    /**
     * 监控白名单用户是否退群
     */
    public void monitorWhiteList() {
        WhitelistInfo whitelistInfo = new WhitelistInfo();
        whitelistInfo.setStatus("1");
        final List<WhitelistInfo> whitelistInfos = whitelistInfoService.selectWhitelistInfoList(whitelistInfo);

        if (whitelistInfos.isEmpty()) {
            return;
        }

        // 获取所有活跃的机器人客户端
        Map<Long, BotClient> activeBots = botManager.getAllBots();
        if (activeBots.isEmpty()) {
            log.warn("没有活跃的机器人客户端");
            return;
        }

        // 用于存储每个群的退群用户列表
        Map<Long, StringBuilder> groupMessages = new HashMap<>();
        // 用于记录用户在任意群中的存在状态
        Map<Long, Boolean> userExistsInAnyGroup = new HashMap<>();
        // 用于记录用户退出的群
        Map<Long, List<Long>> userLeftGroups = new HashMap<>();
        // 所有需要监控的群ID列表
        Set<Long> allGroupIds = new HashSet<>();

        // 获取所有机器人配置的群ID
        for (BotClient bot : activeBots.values()) {
            try {
                if (bot == null || bot.getConfig() == null) {
                    continue;
                }
            } catch (Exception e) {
                // 处理异常，可能是因为机器人未正确初始化
                log.error("获取机器人配置失败: {}", e.getMessage());
                continue;
            }

            QqBotConfig config = bot.getConfig();
            if (config.getGroupIds() != null) {
                allGroupIds.addAll(Arrays.stream(config.getGroupIds().split(","))
                        .map(Long::parseLong)
                        .collect(Collectors.toSet()));
            }
        }

        boolean isFail = false;
        // 获取启用机器人的群员列表
        JSONObject request = new JSONObject();
        request.put("no_cache", false);

        // 遍历所有群，检查每个用户的存在状态
        for (Long groupId : allGroupIds) {
            request.put("group_id", String.valueOf(groupId));
            groupMessages.put(groupId, new StringBuilder());

            // 找到负责该群的机器人
            BotClient responsibleBot = findResponsibleBot(activeBots, groupId);
            if (responsibleBot == null) {
                log.warn("群 {} 没有对应的机器人客户端", groupId);
                isFail = true;
                continue;
            }

            // 使用机器人的配置发送请求
            String botUrl = responsibleBot.getConfig().getHttpUrl();
            HttpResponse response = null;
            try {
                response = HttpUtil
                        .createPost(botUrl + BotApi.GET_GROUP_MEMBER_LIST)
                        .header("Authorization", "Bearer " + responsibleBot.getConfig().getToken())
                        .body(request.toJSONString())
                        .timeout(5000)
                        .execute();
            } catch (Exception e) {
                log.error("群 {} 获取成员列表失败: {}", groupId, e.getMessage());
                isFail = true;
                continue;
            }
            if (response == null || !response.isOk()) {
                log.warn("群 {} 获取成员列表失败", groupId);
                isFail = true;
                continue;
            }

            final JSONObject jsonObject = JSONObject.parseObject(response.body());
            if ((jsonObject.containsKey("retcode") && jsonObject.getInteger("retcode") != 0) || jsonObject.getJSONArray("data") == null) {
                log.warn("群 {} 获取成员列表失败: {}", groupId, jsonObject);
                isFail = true;
                continue;
            } else {
                log.debug("群 {} 成员列表获取成功: {}", groupId, jsonObject);
            }

            final List<JSONObject> members = jsonObject.getJSONArray("data").toJavaList(JSONObject.class);
            isFail = members.isEmpty();
            // 检查每个白名单用户在当前群中的状态
            whitelistInfos.forEach(whitelist -> {
                Long userId = Long.parseLong(whitelist.getQqNum());

                // 检查用户是否在当前群中
                boolean existsInCurrentGroup = members.stream()
                        .anyMatch(member -> userId.equals(member.getLong("user_id")));

                // 更新用户在任意群中的存在状态
                if (existsInCurrentGroup) {
                    userExistsInAnyGroup.put(userId, true);
                }

                // 如果用户不在当前群中，记录到退群列表
                if (!existsInCurrentGroup) {
                    userLeftGroups.computeIfAbsent(userId, k -> new ArrayList<>()).add(groupId);
                }
            });
        }

        // 避免出现空数据移除全部数据
        if (isFail) return;

        // 处理所有不在任何群中的用户
        whitelistInfos.forEach(whitelist -> {
            Long userId = Long.parseLong(whitelist.getQqNum());

            // 如果用户不在任何群中
            if (!userExistsInAnyGroup.containsKey(userId)) {
                // 移除白名单
                whitelist.setAddState("true");
                whitelist.setRemoveReason("用户退群-同步");
                whitelistInfoService.updateWhitelistInfo(whitelist, "system");

                // 在所有相关群中添加通知消息
                List<Long> leftGroups = userLeftGroups.getOrDefault(userId, new ArrayList<>(allGroupIds));
                leftGroups.forEach(groupId -> {
                    groupMessages.get(groupId)
                            .append("\n- 用户：")
                            .append(whitelist.getUserName())
                            .append("(")
                            .append(userId)
                            .append(")");
                });

                log.info("用户 {} ({}) 已不在任何群中，已移除白名单", whitelist.getUserName(), userId);
            }
        });

        // 发送群通知
        groupMessages.forEach((groupId, messageBuilder) -> {
            String groupMessage = messageBuilder.toString();
            if (!groupMessage.isEmpty()) {
                // 找到负责该群的机器人
                BotClient responsibleBot = findResponsibleBot(activeBots, groupId);
                if (responsibleBot == null) {
                    log.error("群 {} 没有对应的机器人客户端，无法发送通知", groupId);
                    return;
                }

                // 构建消息对象
                JSONObject msgRequest = new JSONObject();
                msgRequest.put("group_id", groupId.toString());
                msgRequest.put("message", "⚠️退群白名单移除通知：\n以下用户已退群并移除白名单：" + groupMessage);

                // 发送群消息
                String response = HttpUtil.createPost(responsibleBot.getConfig().getHttpUrl() + BotApi.SEND_GROUP_MSG)
                        .header("Authorization", "Bearer " + responsibleBot.getConfig().getToken())
                        .body(msgRequest.toJSONString())
                        .execute()
                        .body();
                if (response != null) {
                    JSONObject result = JSONObject.parseObject(response);
                    if (result.getInteger("retcode") != 0) {
                        log.error("群 {} 发送退群通知失败: {}", groupId, result.getString("msg"));
                    } else {
                        log.info("群 {} 退群移除白名单通知已发送: {}", groupId, groupMessage);
                    }
                }
            }
        });
    }

    /**
     * 查找负责特定群的机器人客户端
     *
     * @param activeBots 活跃的机器人客户端
     * @param groupId    群号
     * @return 负责该群的机器人客户端，如果没有找到则返回null
     */
    private BotClient findResponsibleBot(Map<Long, BotClient> activeBots, Long groupId) {
        for (BotClient bot : activeBots.values()) {
            QqBotConfig config = bot.getConfig();
            if (config != null && config.getGroupIds() != null) {
                boolean isResponsible = Arrays.stream(config.getGroupIds().split(","))
                        .map(Long::parseLong)
                        .anyMatch(id -> id.equals(groupId));
                if (isResponsible) {
                    return bot;
                }
            }
        }
        return null;
    }
}
