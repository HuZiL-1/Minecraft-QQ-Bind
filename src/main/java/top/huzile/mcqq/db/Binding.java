package top.huzile.mcqq.db;

/**
 * 一条玩家 ↔ QQ 的绑定记录。
 *
 * @param uuid       玩家 UUID(去掉了连字符的字符串形式)
 * @param playerName 玩家名(最近一次登录时更新)
 * @param qq         绑定的 QQ 号
 * @param boundAt    绑定时间(epoch 毫秒)
 */
public record Binding(String uuid, String playerName, long qq, long boundAt) {
}
