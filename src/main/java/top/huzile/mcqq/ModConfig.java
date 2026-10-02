package top.huzile.mcqq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模组配置,存放于 {@code config/mc_qq_mod.json}。
 *
 * <p>首次启动时会自动生成带默认值的配置文件。</p>
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private static final String FILE_NAME = "mc_qq_mod.json";

	public NapCat napcat = new NapCat();
	public Binding binding = new Binding();
	public Messages messages = new Messages();
	public Storage storage = new Storage();

	/** NapCat 连接相关配置。 */
	public static final class NapCat {
		/** NapCat 正向 WebSocket 地址。 */
		public String url = "ws://127.0.0.1:3001";
		/** Access Token,留空表示不鉴权。 */
		public String accessToken = "";
		/** 单个 API 请求的超时时间(秒)。 */
		public int requestTimeoutSeconds = 10;
		/** 心跳超时(秒):超过该时长未收到 NapCat 心跳就主动重连。 */
		public int heartbeatTimeoutSeconds = 90;
		/** 重连退避上限(秒)。 */
		public int maxReconnectBackoffSeconds = 30;
	}

	/** 绑定逻辑相关配置。 */
	public static final class Binding {
		/** 允许执行绑定指令的 QQ 群号;留空表示不限制群。 */
		public List<Long> groupIds = new ArrayList<>();
		/** 绑定指令前缀,可配置多个。 */
		public List<String> commands = new ArrayList<>(List.of("/绑定", "/bind"));
		/** 验证码长度。 */
		public int codeLength = 6;
		/** 验证码有效期(秒)。 */
		public int codeExpireSeconds = 600;
		/** 每个 QQ 每分钟最多尝试绑定次数,防止暴力猜码;<= 0 表示不限制。 */
		public int maxAttemptsPerMinute = 5;
		/** 一个 QQ 是否允许绑定多个游戏账号。 */
		public boolean qqCanBindMultipleAccounts = false;
		/** 一个游戏账号是否允许绑定多个 QQ。 */
		public boolean accountCanBindMultipleQq = false;
		/** 对 QQ 消息是否回复绑定结果。 */
		public boolean replyInGroup = true;
		/** {@code /qqmod} 管理指令所需的最低权限等级(0-4)。 */
		public int adminPermissionLevel = 2;
	}

	/** 文本模板,支持占位符 {@code {group}}、{@code {prefix}}、{@code {code}}、{@code {player}}、{@code {qq}}。 */
	public static final class Messages {
		public String kick = "你还没绑定QQ号,请到QQ群 {group} 发送 {prefix} {code} 完成绑定!";
		public String bindSuccess = "绑定成功!QQ {qq} 已绑定玩家 {player}。";
		public String bindQqTaken = "该 QQ 已绑定玩家 {player},如需更换请先联系管理员解绑。";
		public String bindPlayerTaken = "玩家 {player} 已绑定其它 QQ,如需更换请先联系管理员解绑。";
		public String bindCodeInvalid = "验证码无效或已过期,请重新进入服务器获取新的验证码。";
		public String bindUsage = "用法:{prefix} {code} —— 验证码在未绑定被踢出服务器时显示。";
		public String bindRateLimited = "尝试过于频繁,请稍后再试。";
		public String queryNotBound = "玩家 {player} 未绑定 QQ。";
		public String queryResult = "玩家 {player} 绑定的 QQ:{qq}";
		public String unbindSuccess = "已解绑玩家 {player} 的 QQ。";
		public String unbindNotBound = "解绑失败:玩家 {player} 未绑定 QQ。";
		public String reloadSuccess = "配置已重新加载。";
		public String statusConnected = "NapCat 已连接:{url}";
		public String statusDisconnected = "NapCat 未连接。";
	}

	/** 存储相关配置。 */
	public static final class Storage {
		/** SQLite 数据库文件路径,相对路径以游戏根目录为基准。 */
		public String file = "config/mc_qq_mod/bindings.db";
	}

	/** 载入配置;文件不存在时写入一份默认配置。 */
	public static ModConfig load() {
		Path path = configPath();
		ModConfig config = new ModConfig();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | RuntimeException e) {
				McQqMod.LOGGER.error("读取配置文件失败,将使用默认配置:{}", path, e);
			}
		}

		config.normalize();
		config.save(path);
		return config;
	}

	/** 重新从磁盘载入配置,并就地更新当前实例(保持已有引用有效)。 */
	public void reload() {
		ModConfig fresh = load();
		this.napcat = fresh.napcat;
		this.binding = fresh.binding;
		this.messages = fresh.messages;
		this.storage = fresh.storage;
	}

	/** 补齐缺失或非法的字段,避免用户手改配置后出现空指针。 */
	public void normalize() {
		if (napcat == null) napcat = new NapCat();
		if (binding == null) binding = new Binding();
		if (messages == null) messages = new Messages();
		if (storage == null) storage = new Storage();
		if (binding.groupIds == null) binding.groupIds = new ArrayList<>();
		if (binding.commands == null || binding.commands.isEmpty()) {
			binding.commands = new ArrayList<>(List.of("/绑定", "/bind"));
		}
		if (binding.codeLength < 4) binding.codeLength = 4;
		if (binding.codeLength > 16) binding.codeLength = 16;
		if (binding.codeExpireSeconds < 30) binding.codeExpireSeconds = 30;
		if (binding.adminPermissionLevel < 0) binding.adminPermissionLevel = 0;
		if (binding.adminPermissionLevel > 4) binding.adminPermissionLevel = 4;
		if (storage.file == null || storage.file.isBlank()) storage.file = "config/mc_qq_mod/bindings.db";
		if (napcat.requestTimeoutSeconds < 1) napcat.requestTimeoutSeconds = 10;
		if (napcat.heartbeatTimeoutSeconds < 10) napcat.heartbeatTimeoutSeconds = 90;
		if (napcat.maxReconnectBackoffSeconds < 1) napcat.maxReconnectBackoffSeconds = 30;
	}

	/** 数据库文件的绝对路径。 */
	public Path storagePath() {
		Path path = Path.of(storage.file);
		return path.isAbsolute() ? path : FabricLoader.getInstance().getGameDir().resolve(path);
	}

	/** 渲染 {@code {} } 占位符。 */
	public static String render(String template, Object... keyValuePairs) {
		String result = template == null ? "" : template;
		for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
			result = result.replace("{" + keyValuePairs[i] + "}", String.valueOf(keyValuePairs[i + 1]));
		}
		return result;
	}

	private void save(Path path) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			McQqMod.LOGGER.error("写入配置文件失败:{}", path, e);
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
