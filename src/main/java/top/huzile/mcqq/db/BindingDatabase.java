package top.huzile.mcqq.db;

import top.huzile.mcqq.McQqMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 数据访问层。
 *
 * <p>驱动由 jar-in-jar 打包进模组 jar,服务器端无需额外安装。所有方法都加了
 * {@code synchronized},因为 QQ 消息回调线程与服务器主线程会并发访问。</p>
 *
 * <p>表结构以 {@code (uuid, qq)} 为复合主键,因此天然支持「一个 QQ 绑多个账号」
 * 与「一个账号绑多个 QQ」;是否允许由 {@code BindingService} 按配置裁决。</p>
 *
 * <p>连接在关闭后可再次打开(集成服务器重复开关存档时用得上),因此实例本身
 * 可以长期持有。</p>
 */
public final class BindingDatabase implements AutoCloseable {
	/**
	 * 持有驱动实例的强引用。sqlite-jdbc 的驱动会随其类加载器被 GC 而注销,
	 * 在 Fabric 的类加载器结构下必须抓住不放。
	 */
	@SuppressWarnings("unused")
	private static final Object DRIVER_KEEPALIVE;

	static {
		Object driver;
		try {
			Class<?> clazz = Class.forName("org.sqlite.JDBC");
			driver = clazz.getDeclaredConstructor().newInstance();
			McQqMod.LOGGER.debug("SQLite JDBC 驱动已加载:{}", clazz.getName());
		} catch (ReflectiveOperationException e) {
			driver = null;
			McQqMod.LOGGER.error("无法加载 SQLite JDBC 驱动", e);
		}
		DRIVER_KEEPALIVE = driver;
	}

	private static final String BINDING_COLUMNS = "uuid, player_name, qq, bound_at";

	private final Path file;
	private Connection connection;

	public BindingDatabase(Path file) throws SQLException {
		this.file = file.toAbsolutePath();
		open();
	}

	/** 打开(或重新打开)连接。已打开时为空操作。 */
	public synchronized void open() throws SQLException {
		if (connection != null) {
			return;
		}

		Path parent = file.getParent();
		if (parent != null) {
			try {
				Files.createDirectories(parent);
			} catch (Exception e) {
				throw new SQLException("无法创建数据库目录:" + parent, e);
			}
		}

		Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file);
		try (Statement statement = opened.createStatement()) {
			statement.executeUpdate("PRAGMA journal_mode = WAL");
			statement.executeUpdate("PRAGMA synchronous = NORMAL");
			statement.executeUpdate("""
					CREATE TABLE IF NOT EXISTS bindings (
						uuid        TEXT    NOT NULL,
						player_name TEXT    NOT NULL,
						qq          INTEGER NOT NULL,
						bound_at    INTEGER NOT NULL,
						PRIMARY KEY (uuid, qq)
					)""");
			statement.executeUpdate("""
					CREATE TABLE IF NOT EXISTS pending_codes (
						uuid        TEXT    PRIMARY KEY,
						player_name TEXT    NOT NULL,
						code        TEXT    NOT NULL UNIQUE,
						expires_at  INTEGER NOT NULL
					)""");
			statement.executeUpdate(
					"CREATE INDEX IF NOT EXISTS idx_bindings_name ON bindings (player_name COLLATE NOCASE)");
			statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bindings_qq ON bindings (qq)");
		} catch (SQLException e) {
			opened.close();
			throw e;
		}
		connection = opened;
	}

	public synchronized boolean isOpen() {
		try {
			return connection != null && !connection.isClosed();
		} catch (SQLException e) {
			return false;
		}
	}

	@Override
	public synchronized void close() {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		} catch (SQLException e) {
			McQqMod.LOGGER.error("关闭数据库失败", e);
		} finally {
			connection = null;
		}
	}

	// ---------------------------------------------------------------- bindings

	public synchronized List<Binding> findBindingsByUuid(String uuid) {
		return queryBindings("SELECT " + BINDING_COLUMNS + " FROM bindings WHERE uuid = ?", uuid);
	}

	public synchronized List<Binding> findBindingsByQq(long qq) {
		return queryBindings("SELECT " + BINDING_COLUMNS + " FROM bindings WHERE qq = ?", qq);
	}

	public synchronized List<Binding> findBindingsByName(String playerName) {
		return queryBindings("SELECT " + BINDING_COLUMNS + " FROM bindings WHERE player_name = ? COLLATE NOCASE",
				playerName);
	}

	public synchronized List<Binding> listBindings() {
		return queryBindings("SELECT " + BINDING_COLUMNS + " FROM bindings ORDER BY bound_at");
	}

	/** 该玩家是否绑定过任意 QQ。 */
	public synchronized boolean hasAnyBinding(String uuid) {
		return count("SELECT COUNT(*) FROM bindings WHERE uuid = ?", uuid) > 0;
	}

	/** 该玩家是否已绑定指定的这个 QQ。 */
	public synchronized boolean hasBinding(String uuid, long qq) {
		return count("SELECT COUNT(*) FROM bindings WHERE uuid = ? AND qq = ?", uuid, qq) > 0;
	}

	public synchronized void insertBinding(String uuid, String playerName, long qq) {
		try (PreparedStatement ps = connection().prepareStatement(
				"INSERT INTO bindings (" + BINDING_COLUMNS + ") VALUES (?, ?, ?, ?) " +
						"ON CONFLICT(uuid, qq) DO UPDATE SET player_name = excluded.player_name")) {
			ps.setString(1, uuid);
			ps.setString(2, playerName);
			ps.setLong(3, qq);
			ps.setLong(4, System.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			McQqMod.LOGGER.error("写入绑定记录失败:uuid={} qq={}", uuid, qq, e);
		}
	}

	/** 更新玩家名(改名后保持记录同步)。 */
	public synchronized void updatePlayerName(String uuid, String playerName) {
		try (PreparedStatement ps = connection().prepareStatement(
				"UPDATE bindings SET player_name = ? WHERE uuid = ? AND player_name <> ?")) {
			ps.setString(1, playerName);
			ps.setString(2, uuid);
			ps.setString(3, playerName);
			ps.executeUpdate();
		} catch (SQLException e) {
			McQqMod.LOGGER.error("更新玩家名失败:uuid={}", uuid, e);
		}
	}

	public synchronized int deleteBindingsByUuid(String uuid) {
		return update("DELETE FROM bindings WHERE uuid = ?", uuid);
	}

	public synchronized int deleteBindingsByQq(long qq) {
		return update("DELETE FROM bindings WHERE qq = ?", qq);
	}

	public synchronized int deleteBindingsByName(String playerName) {
		return update("DELETE FROM bindings WHERE player_name = ? COLLATE NOCASE", playerName);
	}

	// ----------------------------------------------------------- pending codes

	/** 写入(或覆盖)某玩家的待用验证码。 */
	public synchronized void putPendingCode(String uuid, String playerName, String code, long expiresAt) {
		try (PreparedStatement ps = connection().prepareStatement(
				"INSERT INTO pending_codes (uuid, player_name, code, expires_at) VALUES (?, ?, ?, ?) " +
						"ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name, " +
						"code = excluded.code, expires_at = excluded.expires_at")) {
			ps.setString(1, uuid);
			ps.setString(2, playerName);
			ps.setString(3, code);
			ps.setLong(4, expiresAt);
			ps.executeUpdate();
		} catch (SQLException e) {
			McQqMod.LOGGER.error("写入验证码失败:uuid={}", uuid, e);
		}
	}

	public synchronized Optional<PendingCode> findPendingCodeByUuid(String uuid) {
		return queryPending("SELECT uuid, player_name, code, expires_at FROM pending_codes WHERE uuid = ?", uuid);
	}

	public synchronized Optional<PendingCode> findPendingCode(String code) {
		return queryPending(
				"SELECT uuid, player_name, code, expires_at FROM pending_codes WHERE code = ? COLLATE NOCASE", code);
	}

	public synchronized void deletePendingCodeByUuid(String uuid) {
		update("DELETE FROM pending_codes WHERE uuid = ?", uuid);
	}

	/** 删除所有已过期的验证码,返回删除条数。 */
	public synchronized int deleteExpiredPendingCodes(long now) {
		return update("DELETE FROM pending_codes WHERE expires_at < ?", now);
	}

	// ---------------------------------------------------------------- helpers

	/** 取得可用连接;若已关闭则重新打开。 */
	private Connection connection() throws SQLException {
		if (connection == null || connection.isClosed()) {
			open();
		}
		if (connection == null) {
			throw new SQLException("数据库连接不可用");
		}
		return connection;
	}

	private List<Binding> queryBindings(String sql, Object... args) {
		List<Binding> result = new ArrayList<>();
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			bind(ps, args);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					result.add(new Binding(
							rs.getString("uuid"),
							rs.getString("player_name"),
							rs.getLong("qq"),
							rs.getLong("bound_at")));
				}
			}
		} catch (SQLException e) {
			McQqMod.LOGGER.error("查询绑定记录失败", e);
		}
		return result;
	}

	private Optional<PendingCode> queryPending(String sql, Object... args) {
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			bind(ps, args);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return Optional.of(new PendingCode(
							rs.getString("uuid"),
							rs.getString("player_name"),
							rs.getString("code"),
							rs.getLong("expires_at")));
				}
			}
		} catch (SQLException e) {
			McQqMod.LOGGER.error("查询验证码失败", e);
		}
		return Optional.empty();
	}

	private int count(String sql, Object... args) {
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			bind(ps, args);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			McQqMod.LOGGER.error("计数失败", e);
			return 0;
		}
	}

	private int update(String sql, Object... args) {
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			bind(ps, args);
			return ps.executeUpdate();
		} catch (SQLException e) {
			McQqMod.LOGGER.error("执行更新失败", e);
			return 0;
		}
	}

	private static void bind(PreparedStatement ps, Object... args) throws SQLException {
		for (int i = 0; i < args.length; i++) {
			ps.setObject(i + 1, args[i]);
		}
	}
}
