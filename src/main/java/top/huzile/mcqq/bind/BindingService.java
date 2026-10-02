package top.huzile.mcqq.bind;

import top.huzile.mcqq.ModConfig;
import top.huzile.mcqq.McQqMod;
import top.huzile.mcqq.db.Binding;
import top.huzile.mcqq.db.BindingDatabase;
import top.huzile.mcqq.db.PendingCode;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 绑定业务逻辑。这里不依赖任何 Minecraft 类型,便于单独理解与测试。
 */
public final class BindingService {
	private final ModConfig config;
	private final BindingDatabase database;
	private final SecureRandom random = new SecureRandom();
	/** QQ -> 最近的尝试时间戳,用于限流。 */
	private final Map<Long, Deque<Long>> attempts = new ConcurrentHashMap<>();

	public BindingService(ModConfig config, BindingDatabase database) {
		this.config = config;
		this.database = database;
	}

	/** 该玩家是否绑定过至少一个 QQ(决定能否进服)。 */
	public boolean isBound(String uuid) {
		return database.hasAnyBinding(uuid);
	}

	public List<Binding> findByUuid(String uuid) {
		return database.findBindingsByUuid(uuid);
	}

	public List<Binding> findByQq(long qq) {
		return database.findBindingsByQq(qq);
	}

	public List<Binding> findByName(String playerName) {
		return database.findBindingsByName(playerName);
	}

	/** 玩家改名后同步数据库中的名字。 */
	public void updatePlayerName(String uuid, String playerName) {
		database.updatePlayerName(uuid, playerName);
	}

	/**
	 * 取得该玩家当前的验证码;若没有有效验证码则生成一个新的。
	 *
	 * <p>玩家反复重连时会复用同一个未过期的验证码,避免刚记下就被换掉。</p>
	 */
	public String ensureCode(String uuid, String playerName) {
		long now = System.currentTimeMillis();
		Optional<PendingCode> existing = database.findPendingCodeByUuid(uuid);
		if (existing.isPresent()) {
			PendingCode pending = existing.get();
			if (pending.expiresAt() > now && pending.playerName().equals(playerName)) {
				return pending.code();
			}
		}

		String code = generateUniqueCode();
		database.putPendingCode(uuid, playerName, code, now + config.binding.codeExpireSeconds * 1000L);
		return code;
	}

	/**
	 * 处理一次「群内发送验证码」的绑定请求。
	 *
	 * @param rawCode 用户发来的验证码原文
	 * @param qq      发消息的 QQ 号
	 */
	public BindResult bind(String rawCode, long qq) {
		long now = System.currentTimeMillis();
		database.deleteExpiredPendingCodes(now);

		String code = rawCode.trim().toUpperCase(Locale.ROOT);
		if (code.isEmpty()) {
			return BindResult.of(BindResult.Status.CODE_INVALID);
		}

		Optional<PendingCode> pendingOpt = database.findPendingCode(code);
		if (pendingOpt.isEmpty()) {
			return BindResult.of(BindResult.Status.CODE_INVALID);
		}

		PendingCode pending = pendingOpt.get();
		if (pending.expiresAt() < now) {
			database.deletePendingCodeByUuid(pending.uuid());
			return BindResult.of(BindResult.Status.CODE_INVALID);
		}

		// 同一组合重复提交 → 幂等视为成功
		if (database.hasBinding(pending.uuid(), qq)) {
			database.deletePendingCodeByUuid(pending.uuid());
			return new BindResult(BindResult.Status.SUCCESS,
					new Binding(pending.uuid(), pending.playerName(), qq, now));
		}

		if (!config.binding.qqCanBindMultipleAccounts) {
			for (Binding existing : database.findBindingsByQq(qq)) {
				if (!existing.uuid().equals(pending.uuid())) {
					return new BindResult(BindResult.Status.QQ_TAKEN, existing);
				}
			}
		}

		if (!config.binding.accountCanBindMultipleQq) {
			for (Binding existing : database.findBindingsByUuid(pending.uuid())) {
				if (existing.qq() != qq) {
					return new BindResult(BindResult.Status.PLAYER_TAKEN, existing);
				}
			}
		}

		database.insertBinding(pending.uuid(), pending.playerName(), qq);
		database.deletePendingCodeByUuid(pending.uuid());
		McQqMod.LOGGER.info("绑定成功:玩家 {} ({}) <-> QQ {}", pending.playerName(), pending.uuid(), qq);
		return new BindResult(BindResult.Status.SUCCESS,
				new Binding(pending.uuid(), pending.playerName(), qq, now));
	}

	/** 解绑(按玩家名,大小写不敏感),返回被删除的记录数。 */
	public int unbindByName(String playerName) {
		int removed = database.deleteBindingsByName(playerName);
		if (removed > 0) {
			McQqMod.LOGGER.info("已解绑玩家 {} 的 {} 条绑定", playerName, removed);
		}
		return removed;
	}

	/** 清掉所有过期验证码,返回清理条数。 */
	public int cleanupExpiredCodes() {
		return database.deleteExpiredPendingCodes(System.currentTimeMillis());
	}

	/** 该 QQ 是否已超过每分钟尝试上限。命中时会记一次尝试。 */
	public boolean isRateLimited(long qq) {
		int max = config.binding.maxAttemptsPerMinute;
		if (max <= 0) {
			return false;
		}

		Deque<Long> timestamps = attempts.computeIfAbsent(qq, key -> new ArrayDeque<>());
		long now = System.currentTimeMillis();
		long cutoff = now - 60_000L;
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
				timestamps.pollFirst();
			}
			if (timestamps.size() >= max) {
				return true;
			}
			timestamps.addLast(now);
			return false;
		}
	}

	/** 把某个玩家的所有绑定 QQ 拼成一行文本,供查询指令使用。 */
	public String describeQqList(String playerName) {
		List<Binding> bindings = database.findBindingsByName(playerName);
		if (bindings.isEmpty()) {
			return null;
		}
		return bindings.stream().map(b -> String.valueOf(b.qq())).collect(Collectors.joining("、"));
	}

	/** 渲染踢出界面显示的提示(填入群号、指令前缀与验证码)。 */
	public String renderKickMessage(String code) {
		String groups = config.binding.groupIds.isEmpty()
				? "指定群"
				: config.binding.groupIds.stream().map(String::valueOf).collect(Collectors.joining("、"));
		String prefix = config.binding.commands.isEmpty() ? "/绑定" : config.binding.commands.get(0);
		return ModConfig.render(config.messages.kick,
				"group", groups,
				"prefix", prefix,
				"code", code);
	}

	private String generateUniqueCode() {
		for (int i = 0; i < 32; i++) {
			String code = CodeGenerator.generate(config.binding.codeLength, random);
			if (database.findPendingCode(code).isEmpty()) {
				return code;
			}
		}
		// 走到这里说明验证码空间几乎被占满,加长再试一次。
		return CodeGenerator.generate(config.binding.codeLength + 2, random);
	}
}
