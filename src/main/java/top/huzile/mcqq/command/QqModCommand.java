package top.huzile.mcqq.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionCheck;
import top.huzile.mcqq.ModConfig;
import top.huzile.mcqq.bind.BindingService;
import top.huzile.mcqq.qq.NapCatClient;

import java.util.function.Supplier;

/**
 * {@code /qqmod} 管理指令。仅限权限等级达到配置要求的玩家(默认 2 级,即 OP 常用等级)。
 *
 * <ul>
 *   <li>{@code /qqmod unbind <玩家>} —— 解绑</li>
 *   <li>{@code /qqmod query <玩家>} —— 查询绑定</li>
 *   <li>{@code /qqmod status} —— 查看 NapCat 连接状态</li>
 *   <li>{@code /qqmod reload} —— 重载配置</li>
 * </ul>
 */
public final class QqModCommand implements CommandRegistrationCallback {
	private final ModConfig config;
	private final BindingService service;
	private final Supplier<NapCatClient> clientSupplier;

	public QqModCommand(ModConfig config, BindingService service, Supplier<NapCatClient> clientSupplier) {
		this.config = config;
		this.service = service;
		this.clientSupplier = clientSupplier;
	}

	@Override
	public void register(CommandDispatcher<CommandSourceStack> dispatcher,
						 CommandBuildContext buildContext,
						 Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("qqmod")
				.requires(Commands.hasPermission(requiredPermission()))
				.then(Commands.literal("unbind")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(context -> unbind(context.getSource(),
										StringArgumentType.getString(context, "player")))))
				.then(Commands.literal("query")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(context -> query(context.getSource(),
										StringArgumentType.getString(context, "player")))))
				.then(Commands.literal("status")
						.executes(context -> status(context.getSource())))
				.then(Commands.literal("reload")
						.executes(context -> reload(context.getSource()))));
	}

	private int unbind(CommandSourceStack source, String playerName) {
		int removed = service.unbindByName(playerName);
		if (removed == 0) {
			source.sendFailure(Component.literal(
					ModConfig.render(config.messages.unbindNotBound, "player", playerName)));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				ModConfig.render(config.messages.unbindSuccess, "player", playerName)), true);
		return removed;
	}

	private int query(CommandSourceStack source, String playerName) {
		String qqList = service.describeQqList(playerName);
		if (qqList == null) {
			source.sendFailure(Component.literal(
					ModConfig.render(config.messages.queryNotBound, "player", playerName)));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				ModConfig.render(config.messages.queryResult, "player", playerName, "qq", qqList)), false);
		return 1;
	}

	private int status(CommandSourceStack source) {
		NapCatClient client = clientSupplier.get();
		String message = client != null && client.isConnected()
				? ModConfig.render(config.messages.statusConnected, "url", client.url())
				: config.messages.statusDisconnected;
		source.sendSuccess(() -> Component.literal(message), false);
		return client != null && client.isConnected() ? 1 : 0;
	}

	private int reload(CommandSourceStack source) {
		config.reload();
		source.sendSuccess(() -> Component.literal(config.messages.reloadSuccess), true);
		return 1;
	}

	/** 把配置里的 0-4 等级映射为原版权限检查。 */
	private PermissionCheck requiredPermission() {
		return switch (config.binding.adminPermissionLevel) {
			case 0 -> Commands.LEVEL_ALL;
			case 1 -> Commands.LEVEL_MODERATORS;
			case 2 -> Commands.LEVEL_GAMEMASTERS;
			case 3 -> Commands.LEVEL_ADMINS;
			default -> Commands.LEVEL_OWNERS;
		};
	}
}
