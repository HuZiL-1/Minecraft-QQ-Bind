package top.huzile.mcqq.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/** OneBot v11 消息段(string / array 两种格式)的读写辅助。 */
public final class QqMessages {
	/** 字符串格式消息里内嵌的 CQ 码,如 {@code [CQ:at,qq=123]}。 */
	private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");

	private QqMessages() {
	}

	/**
	 * 从消息字段中提取纯文本。
	 *
	 * <p>NapCat 既可能下发字符串(含 CQ 码),也可能下发消息段数组,这里统一处理。</p>
	 */
	public static String extractPlainText(JsonElement message) {
		if (message == null || message.isJsonNull()) {
			return "";
		}

		if (message.isJsonPrimitive()) {
			return CQ_CODE.matcher(message.getAsString()).replaceAll("").trim();
		}

		if (message.isJsonArray()) {
			StringBuilder builder = new StringBuilder();
			for (JsonElement element : message.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject segment = element.getAsJsonObject();
				if (!"text".equals(optString(segment, "type"))) {
					continue;
				}
				JsonElement data = segment.get("data");
				if (data != null && data.isJsonObject()) {
					builder.append(optString(data.getAsJsonObject(), "text"));
				}
			}
			return builder.toString().trim();
		}

		return "";
	}

	/** 构造「@某人 + 文本」的消息段数组。 */
	public static JsonArray atAndText(long qq, String text) {
		JsonArray array = new JsonArray();

		JsonObject at = new JsonObject();
		at.addProperty("type", "at");
		JsonObject atData = new JsonObject();
		atData.addProperty("qq", String.valueOf(qq));
		at.add("data", atData);
		array.add(at);

		JsonObject textSegment = new JsonObject();
		textSegment.addProperty("type", "text");
		JsonObject textData = new JsonObject();
		textData.addProperty("text", " " + text);
		textSegment.add("data", textData);
		array.add(textSegment);

		return array;
	}

	/** 构造纯文本的消息段数组。 */
	public static JsonArray text(String text) {
		JsonArray array = new JsonArray();
		JsonObject segment = new JsonObject();
		segment.addProperty("type", "text");
		JsonObject data = new JsonObject();
		data.addProperty("text", text);
		segment.add("data", data);
		array.add(segment);
		return array;
	}

	static String optString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}
}
