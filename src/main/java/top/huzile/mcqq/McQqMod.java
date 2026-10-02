package top.huzile.mcqq;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.huzile.mcqq.bind.BindingService;
import top.huzile.mcqq.command.QqModCommand;
import top.huzile.mcqq.db.BindingDatabase;
import top.huzile.mcqq.qq.NapCatClient;
import top.huzile.mcqq.qq.QqEventHandler;
import top.huzile.mcqq.server.ServerJoinHandler;

import java.sql.SQLException;

/**
 * Minecraft QQ Bind —— 纯服务端模组。
 *
 * <p>玩家必须先把游戏账号与 QQ 绑定才能进入服务器;绑定方式是到指定 QQ 群
 * 发送「/绑定 验证码」,验证码在玩家被踢出的界面中显示。</p>
 */
public class McQqMod implements ModInitializer {
	public static final String MOD_ID = "mc_qq_mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private ModConfig config;
	private BindingDatabase database;
	private BindingService bindingService;
	private QqEventHandler qqEventHandler;
	private volatile NapCatClient napCatClient;

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		openDatabase();

		if (database == null) {
			LOGGER.error("数据库不可用,绑定功能将无法工作。请检查配置中的 storage.file。");
			return;
		}

		bindingService = new BindingService(config, database);
		int cleaned = bindingService.cleanupExpiredCodes();
		if (cleaned > 0) {
			LOGGER.info("已清理 {} 条过期验证码", cleaned);
		}

		// 未绑定玩家进服即被踢出
		ServerPlayConnectionEvents.JOIN.register(new ServerJoinHandler(bindingService));

		// QQ 侧处理
		qqEventHandler = new QqEventHandler(config, bindingService);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			openDatabase();
			NapCatClient client = new NapCatClient(config, qqEventHandler);
			qqEventHandler.setClient(client);
			napCatClient = client;
			client.start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			NapCatClient client = napCatClient;
			napCatClient = null;
			if (client != null) {
				client.stop();
			}
			if (database != null) {
				// 只是关闭连接;实例保留,重开存档时能再用
				database.close();
			}
		});

		// 管理指令
		CommandRegistrationCallback.EVENT.register(
				new QqModCommand(config, bindingService, () -> napCatClient));

		LOGGER.info("Minecraft QQ Bind 已加载,配置文件:config/mc_qq_mod.json");
	}

	/** 打开数据库连接(集成服务器重开存档时会重新打开已关闭的连接)。 */
	private void openDatabase() {
		try {
			if (database == null) {
				database = new BindingDatabase(config.storagePath());
				LOGGER.info("SQLite 数据库已就绪:{}", config.storagePath());
			} else {
				database.open();
			}
		} catch (SQLException e) {
			LOGGER.error("打开 SQLite 数据库失败:{}", config.storagePath(), e);
		}
	}
}
