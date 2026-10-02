package top.huzile.mcqq.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.huzile.mcqq.ModConfig;
import top.huzile.mcqq.McQqMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NapCat 正向 WebSocket 客户端(OneBot v11)。
 *
 * <p>NapCat 作为服务端,本类主动连接。基于 JDK 自带的 {@link HttpClient} 实现,
 * 不引入任何第三方依赖。连接断开后按指数退避重连。</p>
 */
public final class NapCatClient {
	private static final long INITIAL_BACKOFF_MILLIS = 1_000L;
	private static final long HEARTBEAT_CHECK_INTERVAL_SECONDS = 30L;

	private final ModConfig config;
	private final QqEventHandler eventHandler;
	private final HttpClient httpClient;
	private final ScheduledExecutorService scheduler;
	/** echo -> 等待中的请求。 */
	private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
	/** sendText 不是并发安全的,这里串行化发送。 */
	private final Object sendLock = new Object();

	private volatile WebSocket webSocket;
	private volatile long lastHeartbeatMillis;
	private volatile long backoffMillis = INITIAL_BACKOFF_MILLIS;

	public NapCatClient(ModConfig config, QqEventHandler eventHandler) {
		this.config = config;
		this.eventHandler = eventHandler;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "mc_qq_mod-napcat");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		connect();
		scheduler.scheduleWithFixedDelay(this::checkHeartbeat,
				HEARTBEAT_CHECK_INTERVAL_SECONDS, HEARTBEAT_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		scheduler.shutdownNow();

		WebSocket socket = webSocket;
		webSocket = null;
		if (socket != null) {
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
		}
		failAllPending("客户端已关闭");
	}

	public boolean isConnected() {
		WebSocket socket = webSocket;
		return socket != null && !socket.isOutputClosed();
	}

	public String url() {
		return config.napcat.url;
	}

	// ------------------------------------------------------------- OneBot API

	/** 发送一条群消息。{@code message} 可以是消息段数组。 */
	public void sendGroupMessage(long groupId, JsonArray message) {
		JsonObject params = new JsonObject();
		params.addProperty("group_id", groupId);
		params.add("message", message);
		callApi("send_group_msg", params).exceptionally(error -> {
			McQqMod.LOGGER.warn("发送群消息失败:{}", error.getMessage());
			return null;
		});
	}

	/**
	 * 调用一个 OneBot API,等待响应。
	 *
	 * @return 响应 JSON;出错或超时则 exceptionally 完成
	 */
	public CompletableFuture<JsonObject> callApi(String action, JsonObject params) {
		WebSocket socket = webSocket;
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		if (socket == null) {
			future.completeExceptionally(new IllegalStateException("NapCat 未连接"));
			return future;
		}

		String echo = UUID.randomUUID().toString();
		JsonObject payload = new JsonObject();
		payload.addProperty("action", action);
		payload.add("params", params);
		payload.addProperty("echo", echo);

		pending.put(echo, future);

		try {
			synchronized (sendLock) {
				socket.sendText(payload.toString(), true).whenComplete((ignored, error) -> {
					if (error != null) {
						CompletableFuture<JsonObject> waiting = pending.remove(echo);
						if (waiting != null) {
							waiting.completeExceptionally(error);
						}
					}
				});
			}
		} catch (RuntimeException e) {
			pending.remove(echo);
			future.completeExceptionally(e);
			return future;
		}

		scheduler.schedule(() -> {
			CompletableFuture<JsonObject> waiting = pending.remove(echo);
			if (waiting != null) {
				waiting.completeExceptionally(new TimeoutException("API 请求超时:" + action));
			}
		}, config.napcat.requestTimeoutSeconds, TimeUnit.SECONDS);

		return future;
	}

	// -------------------------------------------------------------- connection

	private void connect() {
		if (!running.get()) {
			return;
		}

		URI uri;
		try {
			uri = URI.create(config.napcat.url);
		} catch (IllegalArgumentException e) {
			McQqMod.LOGGER.error("NapCat 地址非法:{}(将不再重试)", config.napcat.url, e);
			running.set(false);
			return;
		}

		WebSocket.Builder builder = httpClient.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(5));

		String token = config.napcat.accessToken;
		if (token != null && !token.isBlank()) {
			builder.header("Authorization", "Bearer " + token);
		}

		builder.buildAsync(uri, new Listener()).whenComplete((socket, error) -> {
			if (error != null) {
				McQqMod.LOGGER.warn("连接 NapCat 失败:{}({})", config.napcat.url, error.getMessage());
				scheduleReconnect();
			}
		});
	}

	private void scheduleReconnect() {
		if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
			return;
		}

		long delay = backoffMillis;
		backoffMillis = Math.min(backoffMillis * 2, config.napcat.maxReconnectBackoffSeconds * 1000L);
		McQqMod.LOGGER.info("{} 毫秒后重连 NapCat……", delay);

		scheduler.schedule(() -> {
			reconnectScheduled.set(false);
			if (running.get() && webSocket == null) {
				connect();
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private void checkHeartbeat() {
		if (!running.get() || webSocket == null) {
			return;
		}

		long timeout = config.napcat.heartbeatTimeoutSeconds * 1000L;
		if (System.currentTimeMillis() - lastHeartbeatMillis > timeout) {
			forceReconnect("超过 " + config.napcat.heartbeatTimeoutSeconds + " 秒没有任何数据");
			return;
		}

		// 保活:也顺便刷新 lastHeartbeat(响应帧会被 handleFrame 记为一次存活)
		callApi("get_status", new JsonObject()).exceptionally(error -> null);
	}

	/** 主动断开并安排重连。 */
	private void forceReconnect(String reason) {
		McQqMod.LOGGER.warn("主动重连 NapCat:{}", reason);
		WebSocket socket = webSocket;
		webSocket = null;
		if (socket != null) {
			socket.abort();
		}
		failAllPending(reason);
		scheduleReconnect();
	}

	private void handleFrame(String text) {
		// 任何收到的帧都视为存活信号(不依赖 NapCat 是否开启心跳)
		lastHeartbeatMillis = System.currentTimeMillis();

		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			McQqMod.LOGGER.warn("收到非 JSON 帧:{}", text);
			return;
		}
		if (!parsed.isJsonObject()) {
			return;
		}
		JsonObject frame = parsed.getAsJsonObject();

		// 带 echo 的是 API 响应
		if (frame.has("echo") && !frame.get("echo").isJsonNull()) {
			String echo = frame.get("echo").getAsString();
			CompletableFuture<JsonObject> waiting = pending.remove(echo);
			if (waiting != null) {
				waiting.complete(frame);
			}
			return;
		}

		// 心跳等元事件无需进一步处理(存活时间已在方法开头刷新)
		String postType = QqMessages.optString(frame, "post_type");
		if ("meta_event".equals(postType)) {
			return;
		}

		if ("message".equals(postType)) {
			eventHandler.handle(frame);
		}
	}

	private void failAllPending(String reason) {
		for (String echo : pending.keySet()) {
			CompletableFuture<JsonObject> waiting = pending.remove(echo);
			if (waiting != null) {
				waiting.completeExceptionally(new IllegalStateException(reason));
			}
		}
	}

	/** WebSocket 回调。 */
	private final class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket socket) {
			webSocket = socket;
			backoffMillis = INITIAL_BACKOFF_MILLIS;
			reconnectScheduled.set(false);
			lastHeartbeatMillis = System.currentTimeMillis();
			McQqMod.LOGGER.info("已连接 NapCat:{}", config.napcat.url);
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				try {
					handleFrame(text);
				} catch (RuntimeException e) {
					McQqMod.LOGGER.error("处理 NapCat 消息时出错", e);
				}
			}
			socket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			McQqMod.LOGGER.warn("NapCat 连接关闭:code={} reason={}", statusCode, reason);
			webSocket = null;
			failAllPending("连接已关闭");
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			McQqMod.LOGGER.warn("NapCat 连接出错:{}", error.toString());
			webSocket = null;
			failAllPending("连接出错");
			scheduleReconnect();
		}
	}
}
