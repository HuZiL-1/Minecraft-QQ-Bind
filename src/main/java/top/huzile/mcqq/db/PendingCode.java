package top.huzile.mcqq.db;

/**
 * 一条待使用的绑定验证码。
 *
 * @param uuid       申请该验证码的玩家 UUID
 * @param playerName 玩家名
 * @param code       验证码
 * @param expiresAt  过期时间(epoch 毫秒)
 */
public record PendingCode(String uuid, String playerName, String code, long expiresAt) {
}
