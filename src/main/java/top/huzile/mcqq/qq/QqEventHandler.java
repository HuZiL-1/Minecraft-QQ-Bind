package top.huzile.mcqq.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.huzile.mcqq.ModConfig;
import top.huzile.mcqq.McQqMod;
import top.huzile.mcqq.bind.BindResult;
import top.huzile.mcqq.bind.BindingService;
import top.huzile.mcqq.db.Binding;

/**
 * 处理 NapCat 推送的群消息事件:识别绑定指令并调用 {@link BindingService}。
 *
 * <p>运行在 WebSocket 回调线程上,不触碰任何 Minecraft 状态。</p>
 */
public final class QqEventHandler {
	private final ModConfig config;
	private final BindingService service;
	private volatile NapCatClient client;

	public QqEventHandler(ModConfig config, BindingService service) {
		this.config = config;
		this.service = service;
	}

	public void setClient(NapCatClient client) {
		this.client = client;
	}

	public void handle(JsonObject event) {
		if (!"group".equals(QqMessages.optString(event, "message_type"))) {
			return;
		}

		long groupId = asLong(event.get("group_id"), -1L);
		long userId = asLong(event.get("user_id"), -1L);
		if (groupId < 0 || userId < 0) {
			return;
		}

		// 未配置群号时不限群;配置了则只在指定群里响应
		if (!config.binding.groupIds.isEmpty() && !config.binding.groupIds.contains(groupId)) {
			return;
		}

		String text = QqMessages.extractPlainText(event.get("message"));
		if (text.isEmpty()) {
			return;
		}

		for (String prefix : config.binding.commands) {
			String matched = matchPrefix(text, prefix);
			if (matched != null) {
				String argument = text.substring(matched.length()).trim();
				handleBind(groupId, userId, argument);
				return;
			}
		}
	}

	private void handleBind(long groupId, long userId, String argument) {
		if (argument.isEmpty()) {
			reply(groupId, userId, renderUsage());
			return;
		}

		if (service.isRateLimited(userId)) {
			reply(groupId, userId, config.messages.bindRateLimited);
			return;
		}

		BindResult result = service.bind(argument, userId);
		reply(groupId, userId, formatResult(result, userId));
	}

	private String formatResult(BindResult result, long qq) {
		Binding binding = result.binding();
		return switch (result.status()) {
			case SUCCESS -> ModConfig.render(config.messages.bindSuccess,
					"qq", qq,
					"player", binding == null ? "?" : binding.playerName());
			case CODE_INVALID -> config.messages.bindCodeInvalid;
			case QQ_LIMIT_REACHED -> ModConfig.render(config.messages.bindQqLimitReached,
					"max", config.binding.maxAccountsPerQq);
		};
	}

	private String renderUsage() {
		String prefix = config.binding.commands.isEmpty() ? "/绑定" : config.binding.commands.get(0);
		return ModConfig.render(config.messages.bindUsage, "prefix", prefix);
	}

	private void reply(long groupId, long userId, String message) {
		McQqMod.LOGGER.info("[群 {}] 回复 {}:{}", groupId, userId, message);

		NapCatClient napCat = client;
		if (napCat == null || !config.binding.replyInGroup) {
			return;
		}
		napCat.sendGroupMessage(groupId, QqMessages.atAndText(userId, message));
	}

	/** 匹配指令前缀;同时兼容省略前导斜杠的写法(如「绑定 ABC123」)。 */
	private static String matchPrefix(String text, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return null;
		}
		if (text.startsWith(prefix)) {
			return prefix;
		}
		if (prefix.startsWith("/") && text.startsWith(prefix.substring(1))) {
			return prefix.substring(1);
		}
		return null;
	}

	private static long asLong(JsonElement element, long fallback) {
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		try {
			return element.getAsLong();
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
