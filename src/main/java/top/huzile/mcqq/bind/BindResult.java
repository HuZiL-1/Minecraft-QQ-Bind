package top.huzile.mcqq.bind;

import top.huzile.mcqq.db.Binding;

/**
 * 一次绑定尝试的结果。
 *
 * @param status  结果状态
 * @param binding 相关绑定记录,失败时用于向玩家/群说明冲突对象(可能为 {@code null})
 */
public record BindResult(Status status, Binding binding) {
	public enum Status {
		/** 绑定成功。 */
		SUCCESS,
		/** 验证码不存在或已过期。 */
		CODE_INVALID,
		/** 该 QQ 已经绑定了另一个账号。 */
		QQ_TAKEN,
		/** 该玩家账号已经绑定了另一个 QQ。 */
		PLAYER_TAKEN
	}

	public static BindResult of(Status status) {
		return new BindResult(status, null);
	}

	public boolean success() {
		return status == Status.SUCCESS;
	}
}
