package top.huzile.mcqq.server;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import top.huzile.mcqq.bind.BindingService;
import top.huzile.mcqq.McQqMod;

/**
 * 玩家进入服务器时校验绑定状态:未绑定的玩家会被立即踢出,并在断开界面
 * 看到自己的验证码。
 */
public final class ServerJoinHandler implements ServerPlayConnectionEvents.Join {
	private final BindingService service;

	public ServerJoinHandler(BindingService service) {
		this.service = service;
	}

	@Override
	public void onPlayReady(ServerGamePacketListenerImpl listener, PacketSender sender, MinecraftServer server) {
		ServerPlayer player = listener.getPlayer();
		if (player == null) {
			return;
		}

		String uuid = player.getUUID().toString();
		String playerName = player.getScoreboardName();

		if (service.isBound(uuid)) {
			// 玩家改名后保持记录同步
			service.updatePlayerName(uuid, playerName);
			return;
		}

		String code = service.ensureCode(uuid, playerName);
		McQqMod.LOGGER.info("玩家 {} ({}) 未绑定 QQ,已拒绝进入(验证码 {})", playerName, uuid, code);
		listener.disconnect(Component.literal(service.renderKickMessage(code)));
	}
}
